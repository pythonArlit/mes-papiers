package app.mespapiers

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mespapiers.core.docs.Deadlines
import app.mespapiers.core.model.Category
import app.mespapiers.core.model.Field
import app.mespapiers.core.model.FieldType
import app.mespapiers.core.model.Ids
import app.mespapiers.core.model.Limits
import app.mespapiers.core.model.Record
import app.mespapiers.core.model.Templates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

sealed interface VaultScreen {
    data object Home : VaultScreen
    data object List : VaultScreen
    data class Detail(val id: String) : VaultScreen
    data object PickCategory : VaultScreen
    data object Edit : VaultScreen
}

/** Dates saisies et affichées au format JJ/MM/AAAA, stockées en ISO (date civile, sans heure). */
object DateText {
    private val display = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)

    fun toDisplay(iso: String): String = try {
        LocalDate.parse(iso).format(display)
    } catch (e: DateTimeParseException) {
        iso
    }

    fun format(date: LocalDate): String = date.format(display)

    /** Renvoie la date ISO, ou null si la saisie n'est pas une date réelle. */
    fun toIso(text: String): String? = try {
        LocalDate.parse(text.trim(), display).toString()
    } catch (e: DateTimeParseException) {
        null
    }
}

class DraftField(
    val key: String,
    type: FieldType,
    val custom: Boolean,
    val isExpiry: Boolean,
    label: String,
    value: String,
    sensitive: Boolean,
) {
    var type by mutableStateOf(type)
    var label by mutableStateOf(label)
    var value by mutableStateOf(value)
    var sensitive by mutableStateOf(sensitive)
}

/** Fiche en cours de saisie. Vit dans le ViewModel, jamais dans un état sauvegardé. */
class Draft(val id: String?, val category: Category, val createdAtMs: Long, val favorite: Boolean, title: String, tags: String) {
    var title by mutableStateOf(title)
    var tags by mutableStateOf(tags)
    val fields = mutableStateListOf<DraftField>()
}

class VaultViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as MesPapiersApp).container
    val state get() = c.vaultState

    var screen by mutableStateOf<VaultScreen>(VaultScreen.Home)
        private set
    var query by mutableStateOf("")
    var category by mutableStateOf<Category?>(null)
    var draft by mutableStateOf<Draft?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    @get:StringRes
    var error by mutableStateOf<Int?>(null)
        private set

    private var detailCameFromList = false

    fun today(): LocalDate = Deadlines.today(c.clock.wallMillis(), ZoneId.systemDefault())

    private fun categoryLabel(r: Record): String = getApplication<Application>().getString(categoryLabelRes(r.category))

    /** Retour à l'état initial, appelé dès que la session n'est plus ouverte. */
    fun reset() {
        screen = VaultScreen.Home
        query = ""
        category = null
        draft = null
        error = null
    }

    fun load() {
        if (state.loaded || busy) return
        io {
            val loaded = c.vault.loadAll()
            state.set(loaded.records, loaded.unreadable, ::categoryLabel)
        }
    }

    // ------------------------------------------------------------ navigation

    fun showList() {
        error = null
        screen = VaultScreen.List
    }

    fun open(id: String) {
        detailCameFromList = screen == VaultScreen.List
        error = null
        screen = VaultScreen.Detail(id)
    }

    fun back() {
        error = null
        screen = when (val s = screen) {
            VaultScreen.Home -> VaultScreen.Home
            VaultScreen.List, VaultScreen.PickCategory -> VaultScreen.Home
            is VaultScreen.Detail -> if (detailCameFromList) VaultScreen.List else VaultScreen.Home
            VaultScreen.Edit -> {
                val id = draft?.id
                draft = null
                if (id != null) VaultScreen.Detail(id) else VaultScreen.Home
            }
        }
    }

    /** Fiches correspondant à la recherche et au filtre de catégorie. */
    fun results(): List<Record> {
        val ids = if (query.isBlank()) null else state.search(query)
        return state.records.filter { (category == null || it.category == category) && (ids == null || it.id in ids) }
    }

    // ------------------------------------------------------------ édition

    fun newDocument() {
        error = null
        screen = VaultScreen.PickCategory
    }

    fun startDraft(category: Category) {
        val d = Draft(null, category, c.clock.wallMillis(), favorite = false, title = "", tags = "")
        Templates.fieldsFor(category).forEach { d.fields += DraftField(it.key, it.type, false, it.isExpiry, "", "", it.sensitive) }
        draft = d
        screen = VaultScreen.Edit
    }

    fun edit(record: Record) {
        val d = Draft(record.id, record.category, record.createdAtMs, record.favorite, record.title, record.tags.joinToString(", "))
        val existing = record.fields.associateBy { it.key }
        for (spec in Templates.fieldsFor(record.category)) {
            val f = existing[spec.key]?.takeIf { !it.custom }
            d.fields += DraftField(spec.key, spec.type, false, spec.isExpiry, "", f?.let { display(it) } ?: "", f?.sensitive ?: spec.sensitive)
        }
        record.fields.filter { it.custom }.forEach { d.fields += DraftField(it.key, it.type, true, false, it.label, display(it), it.sensitive) }
        draft = d
        error = null
        screen = VaultScreen.Edit
    }

    private fun display(f: Field) = if (f.type == FieldType.DATE) DateText.toDisplay(f.value) else f.value

    fun addCustomField() {
        val d = draft ?: return
        if (d.fields.size >= Limits.MAX_FIELDS) return
        d.fields += DraftField(Ids.new(), FieldType.TEXT, true, false, "", "", false)
    }

    fun removeField(field: DraftField) {
        draft?.fields?.remove(field)
    }

    fun save() {
        val d = draft ?: return
        if (busy) return
        if (d.title.isBlank()) {
            error = R.string.edit_title_required
            return
        }
        val fields = ArrayList<Field>()
        var expiry: LocalDate? = null
        for (f in d.fields) {
            val raw = if (f.type == FieldType.IDENTIFIER) f.value else f.value.trim()
            if (raw.isBlank()) {
                if (f.custom && f.label.isNotBlank()) {
                    error = R.string.edit_custom_value_required
                    return
                }
                continue
            }
            if (f.custom && f.label.isBlank()) {
                error = R.string.edit_custom_label_required
                return
            }
            val value = if (f.type == FieldType.DATE) {
                DateText.toIso(raw) ?: run {
                    error = R.string.edit_date_invalid
                    return
                }
            } else {
                raw
            }
            if (f.isExpiry) expiry = LocalDate.parse(value)
            fields += Field(f.key, f.type, value, f.sensitive, f.custom, if (f.custom) f.label.trim() else "")
        }
        val tags = d.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(Limits.MAX_TAGS)
        val now = c.clock.wallMillis()
        val record = Record(d.id ?: Ids.new(), d.category, d.title.trim(), tags, d.favorite, fields, expiry, d.createdAtMs, now)
        error = null
        io {
            c.vault.put(record)
            replaceInState(record)
            draft = null
            detailCameFromList = false
            screen = VaultScreen.Detail(record.id)
        }
    }

    fun toggleFavorite(record: Record) = io {
        val updated = record.copy(favorite = !record.favorite, updatedAtMs = c.clock.wallMillis())
        c.vault.put(updated)
        replaceInState(updated)
    }

    /** Copie les champs sous un nouvel identifiant (les pièces jointes viendront avec leur jalon). */
    fun duplicate(record: Record) = io {
        val now = c.clock.wallMillis()
        val suffix = getApplication<Application>().getString(R.string.copy_suffix)
        val copy = record.copy(id = Ids.new(), title = record.title + suffix, favorite = false, createdAtMs = now, updatedAtMs = now)
        c.vault.put(copy)
        replaceInState(copy)
        screen = VaultScreen.Detail(copy.id)
    }

    /** Suppression définitive, après confirmation dans l'interface. */
    fun delete(record: Record) = io {
        c.vault.delete(record.id)
        state.set(state.records.filterNot { it.id == record.id }, state.unreadable, ::categoryLabel)
        screen = if (detailCameFromList) VaultScreen.List else VaultScreen.Home
    }

    private fun replaceInState(record: Record) {
        state.set(state.records.filterNot { it.id == record.id } + record, state.unreadable, ::categoryLabel)
    }

    /** Opération sur le coffre hors du fil principal ; un verrouillage survenu entre-temps est signalé. */
    private fun io(block: () -> Unit) {
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                error = R.string.vault_operation_failed
            } finally {
                busy = false
            }
        }
    }
}

@StringRes
fun categoryLabelRes(category: Category): Int = when (category) {
    Category.IDENTITY -> R.string.category_identity
    Category.DIPLOMA -> R.string.category_diploma
    Category.CIVIL_STATUS -> R.string.category_civil_status
    Category.ADDRESS -> R.string.category_address
    Category.BANK -> R.string.category_bank
    Category.OTHER -> R.string.category_other
}

@StringRes
fun fieldLabelRes(key: String): Int = when (key) {
    "type" -> R.string.field_type
    "holder" -> R.string.field_holder
    "number" -> R.string.field_number
    "issuer" -> R.string.field_issuer
    "issued_on" -> R.string.field_issued_on
    "expires_on" -> R.string.field_expires_on
    "title" -> R.string.field_title
    "institution" -> R.string.field_institution
    "obtained_on" -> R.string.field_obtained_on
    "reference" -> R.string.field_reference
    "act_type" -> R.string.field_act_type
    "act_date" -> R.string.field_act_date
    "label" -> R.string.field_label
    "address" -> R.string.field_address
    "city" -> R.string.field_city
    "country" -> R.string.field_country
    "extra" -> R.string.field_extra
    "valid_from" -> R.string.field_valid_from
    "valid_until" -> R.string.field_valid_until
    "bank" -> R.string.field_bank
    "account_number" -> R.string.field_account_number
    "rib" -> R.string.field_rib
    "iban" -> R.string.field_iban
    "bic" -> R.string.field_bic
    "contact" -> R.string.field_contact
    else -> R.string.field_other
}
