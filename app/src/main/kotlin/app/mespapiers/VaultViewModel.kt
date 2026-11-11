package app.mespapiers

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import app.mespapiers.core.model.AttachmentMeta
import app.mespapiers.core.model.AttachmentRole
import app.mespapiers.core.model.FileFormat
import app.mespapiers.core.model.ImportException
import app.mespapiers.core.util.wipe
import app.mespapiers.data.NoSpaceException
import app.mespapiers.platform.ImageRenderer
import app.mespapiers.platform.PdfSession
import kotlinx.coroutines.Job
import java.io.File
import java.io.IOException
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
    data class Viewer(val attachmentId: String) : VaultScreen
}

/** Ce que la visionneuse affiche. Les images déchiffrées ne vivent qu'en mémoire. */
sealed interface ViewerState {
    data object Loading : ViewerState
    class Picture(val bitmap: Bitmap) : ViewerState
    class PdfPage(val bitmap: Bitmap, val index: Int, val count: Int) : ViewerState
    class Failed(@StringRes val message: Int) : ViewerState
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

    /** Octets importés pendant un import en cours, null sinon. */
    var importProgress by mutableStateOf<Long?>(null)
        private set
    var viewer by mutableStateOf<ViewerState>(ViewerState.Loading)
        private set
    private var importJob: Job? = null

    @Volatile
    private var importCancelled = false
    private var pdf: PdfSession? = null
    private var captureFile: File? = null

    fun today(): LocalDate = Deadlines.today(c.clock.wallMillis(), ZoneId.systemDefault())

    private fun categoryLabel(r: Record): String = getApplication<Application>().getString(categoryLabelRes(r.category))

    /** Retour à l'état initial, appelé dès que la session n'est plus ouverte. */
    fun reset() {
        screen = VaultScreen.Home
        query = ""
        category = null
        draft = null
        error = null
        importCancelled = true
        closeViewer()
        captureFile?.delete()
        captureFile = null
    }

    fun load() {
        if (state.loaded || busy) return
        io {
            val loaded = c.vault.loadAll()
            state.set(loaded.records, loaded.unreadable, ::categoryLabel)
            state.attachments = c.attachments.loadAll()
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
            is VaultScreen.Viewer -> {
                val recordId = state.attachments.firstOrNull { it.id == s.attachmentId }?.recordId
                closeViewer()
                if (recordId != null) VaultScreen.Detail(recordId) else VaultScreen.Home
            }
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

    /** Copie les champs sous un nouvel identifiant et, si demandé, les pièces jointes. */
    fun duplicate(record: Record, copyAttachments: Boolean) = io {
        val now = c.clock.wallMillis()
        val suffix = getApplication<Application>().getString(R.string.copy_suffix)
        val copy = record.copy(id = Ids.new(), title = record.title + suffix, favorite = false, createdAtMs = now, updatedAtMs = now)
        c.vault.put(copy)
        replaceInState(copy)
        if (copyAttachments) {
            for (a in attachmentsOf(record.id)) state.attachments = state.attachments + c.attachments.copy(a, copy.id)
        }
        screen = VaultScreen.Detail(copy.id)
    }

    /** Suppression définitive de la fiche et de ses pièces, après confirmation dans l'interface. */
    fun delete(record: Record) = io {
        for (a in attachmentsOf(record.id)) {
            c.attachments.delete(a)
            state.attachments = state.attachments.filterNot { it.id == a.id }
        }
        c.vault.delete(record.id)
        state.set(state.records.filterNot { it.id == record.id }, state.unreadable, ::categoryLabel)
        screen = if (detailCameFromList) VaultScreen.List else VaultScreen.Home
    }

    // ------------------------------------------------------------ pièces jointes

    fun attachmentsOf(recordId: String): List<AttachmentMeta> = state.attachments.filter { it.recordId == recordId }

    fun canAttach(recordId: String): Boolean = attachmentsOf(recordId).size < Limits.MAX_ATTACHMENTS_PER_RECORD

    /** À appeler juste avant d'ouvrir le sélecteur de fichiers ou l'appareil photo. */
    fun beforeExternalActivity() {
        error = null
        c.timers.startGrace(c.clock.elapsedMillis())
    }

    fun importFrom(uri: Uri, recordId: String, role: AttachmentRole) {
        val resolver = c.appContext.contentResolver
        var name = ""
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0) ?: ""
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }
        } catch (e: Exception) {
            // Le fournisseur ne donne ni nom ni taille : on s'en passe.
        }
        startImport(recordId, role, name, size, deleteAfter = null) { resolver.openInputStream(uri) ?: throw IOException("Fichier inaccessible") }
    }

    /** Prépare un fichier privé pour l'appareil photo du système. Null si c'est impossible. */
    fun prepareCapture(): Uri? = try {
        beforeExternalActivity()
        val dir = c.attachments.captureDir.apply { mkdirs() }
        val file = File.createTempFile("photo", ".jpg", dir)
        captureFile = file
        FileProvider.getUriForFile(c.appContext, c.appContext.packageName + ".capture", file)
    } catch (e: Exception) {
        error = R.string.attach_camera_unavailable
        null
    }

    /** La photo de travail est chiffrée si la prise de vue a réussi, et effacée dans tous les cas. */
    fun onCaptureResult(success: Boolean, recordId: String, role: AttachmentRole) {
        val file = captureFile ?: return
        captureFile = null
        if (!success || file.length() == 0L) {
            file.delete()
            return
        }
        val name = getApplication<Application>().getString(R.string.attach_photo_name)
        startImport(recordId, role, name, file.length(), deleteAfter = file) { file.inputStream() }
    }

    fun onCameraMissing() {
        captureFile?.delete()
        captureFile = null
        error = R.string.attach_camera_unavailable
    }

    private fun startImport(recordId: String, role: AttachmentRole, name: String, size: Long, deleteAfter: File?, open: () -> java.io.InputStream) {
        if (importJob?.isActive == true) {
            deleteAfter?.delete()
            return
        }
        if (!canAttach(recordId)) {
            deleteAfter?.delete()
            error = R.string.attach_too_many
            return
        }
        if (size > Limits.MAX_FILE_BYTES) {
            deleteAfter?.delete()
            error = R.string.attach_too_large
            return
        }
        importCancelled = false
        importProgress = 0
        error = null
        importJob = viewModelScope.launch {
            error = try {
                withContext(Dispatchers.IO) {
                    val meta = open().use { input ->
                        c.attachments.import(recordId, role, name, input, size, { importCancelled }) { importProgress = it }
                    }
                    val problem = validate(meta)
                    if (problem != null) {
                        c.attachments.delete(meta)
                        problem
                    } else {
                        state.attachments = state.attachments + meta
                        null
                    }
                }
            } catch (e: ImportException) {
                when (e.reason) {
                    ImportException.Reason.UNSUPPORTED_FORMAT -> R.string.attach_unsupported
                    ImportException.Reason.TOO_LARGE -> R.string.attach_too_large
                    ImportException.Reason.CANCELLED -> null
                }
            } catch (e: NoSpaceException) {
                R.string.attach_no_space
            } catch (e: Exception) {
                R.string.attach_failed
            } finally {
                deleteAfter?.delete()
                importProgress = null
            }
        }
    }

    fun cancelImport() {
        importCancelled = true
    }

    /** Vérifie que la pièce importée s'ouvre réellement ; renvoie le message d'erreur sinon. */
    @StringRes
    private fun validate(meta: AttachmentMeta): Int? {
        val bytes = c.attachments.readAll(meta)
        try {
            if (meta.format == FileFormat.PDF) {
                try {
                    PdfSession(bytes, c.attachments.viewDir).use { if (it.pageCount < 1) return R.string.attach_corrupted }
                } catch (e: SecurityException) {
                    return R.string.attach_pdf_protected
                } catch (e: Exception) {
                    return R.string.attach_corrupted
                }
            } else if (ImageRenderer.decode(bytes) == null) {
                return R.string.attach_corrupted
            }
            return null
        } finally {
            bytes.wipe()
        }
    }

    fun deleteAttachment(meta: AttachmentMeta) = io {
        c.attachments.delete(meta)
        state.attachments = state.attachments.filterNot { it.id == meta.id }
    }

    // ------------------------------------------------------------ visionneuse

    fun openAttachment(meta: AttachmentMeta) {
        closeViewer()
        viewer = ViewerState.Loading
        screen = VaultScreen.Viewer(meta.id)
        viewModelScope.launch {
            viewer = try {
                withContext(Dispatchers.IO) {
                    val bytes = c.attachments.readAll(meta)
                    try {
                        if (meta.format == FileFormat.PDF) {
                            val session = PdfSession(bytes, c.attachments.viewDir)
                            pdf = session
                            ViewerState.PdfPage(session.render(0), 0, session.pageCount)
                        } else {
                            ImageRenderer.decode(bytes)?.let { ViewerState.Picture(it) } ?: ViewerState.Failed(R.string.attach_corrupted)
                        }
                    } finally {
                        bytes.wipe()
                    }
                }
            } catch (e: SecurityException) {
                ViewerState.Failed(R.string.attach_pdf_protected)
            } catch (e: Exception) {
                ViewerState.Failed(R.string.viewer_failed)
            } catch (e: OutOfMemoryError) {
                ViewerState.Failed(R.string.viewer_failed)
            }
        }
    }

    fun showPdfPage(index: Int) {
        val session = pdf ?: return
        if (index !in 0 until session.pageCount) return
        viewModelScope.launch {
            viewer = try {
                withContext(Dispatchers.IO) { ViewerState.PdfPage(session.render(index), index, session.pageCount) }
            } catch (e: Exception) {
                ViewerState.Failed(R.string.viewer_failed)
            }
        }
    }

    /** Ferme le PDF, efface la copie temporaire éventuelle et libère les images. */
    private fun closeViewer() {
        pdf?.close()
        pdf = null
        viewer = ViewerState.Loading
        c.attachments.clearPlainCopies()
    }

    override fun onCleared() {
        closeViewer()
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
