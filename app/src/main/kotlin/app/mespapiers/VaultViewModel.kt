package app.mespapiers

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.mespapiers.core.docs.ExportReminder
import app.mespapiers.core.docs.RecoveryPhrase
import app.mespapiers.data.ExportCancelled
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mespapiers.core.docs.Deadlines
import app.mespapiers.core.docs.DocumentReader
import app.mespapiers.core.docs.Extraction
import app.mespapiers.core.docs.FileSignature
import app.mespapiers.core.model.AttachmentMeta
import app.mespapiers.core.model.AttachmentRole
import app.mespapiers.core.model.Category
import app.mespapiers.core.model.Field
import app.mespapiers.core.model.FieldType
import app.mespapiers.core.model.FileFormat
import app.mespapiers.core.model.Ids
import app.mespapiers.core.model.ImportException
import app.mespapiers.core.model.Limits
import app.mespapiers.core.model.Record
import app.mespapiers.core.model.Templates
import app.mespapiers.core.util.wipe
import app.mespapiers.data.NoSpaceException
import app.mespapiers.platform.ImageRenderer
import app.mespapiers.platform.PdfSession
import app.mespapiers.platform.ScanProcessor
import app.mespapiers.platform.TextReader
import app.mespapiers.platform.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
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
    data object Backup : VaultScreen
}

enum class BackupStep { OVERVIEW, SHOW_PHRASE, CONFIRM_PHRASE }

/** Message de l'écran de sauvegarde. */
class BackupNote(val text: String, val isError: Boolean)

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
    detected: Boolean = false,
) {
    var type by mutableStateOf(type)
    var label by mutableStateOf(label)
    var value by mutableStateOf(value)
    var sensitive by mutableStateOf(sensitive)

    /** Vrai tant que la valeur est celle lue automatiquement sur le document. */
    var detected by mutableStateOf(detected)
}

/** Fiche en cours de saisie. Vit dans le ViewModel, jamais dans un état sauvegardé. */
class Draft(
    val id: String,
    val isNew: Boolean,
    category: Category,
    val createdAtMs: Long,
    val favorite: Boolean,
    title: String,
    tags: String,
    /** Ce qui a été lu sur le document scanné ; null pour une saisie à la main. */
    val extraction: Extraction? = null,
) {
    var category by mutableStateOf(category)
    var title by mutableStateOf(title)
    var titleDetected by mutableStateOf(false)
    var tags by mutableStateOf(tags)
    val fields = mutableStateListOf<DraftField>()

    val detectedCount: Int get() = fields.count { it.detected } + if (titleDetected) 1 else 0
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

    /** Octets importés pendant un import en cours, null sinon. */
    var importProgress by mutableStateOf<Long?>(null)
        private set

    /** Vrai pendant la lecture automatique d'un document. */
    var scanning by mutableStateOf(false)
        private set
    var viewer by mutableStateOf<ViewerState>(ViewerState.Loading)
        private set

    /** Aperçus déchiffrés, en mémoire pendant la session seulement. */
    val thumbs = mutableStateMapOf<String, ImageBitmap>()
    private val thumbsRequested = HashSet<String>()
    private val thumbDispatcher = Dispatchers.IO.limitedParallelism(2)

    private var detailCameFromList = false
    private var viewerReturn: VaultScreen = VaultScreen.Home
    private var importJob: Job? = null

    @Volatile
    private var importCancelled = false
    private var pdf: PdfSession? = null
    private var captureFile: File? = null

    fun today(): LocalDate = Deadlines.today(c.clock.wallMillis(), ZoneId.systemDefault())

    private fun string(@StringRes res: Int) = getApplication<Application>().getString(res)

    private fun categoryLabel(r: Record): String = string(categoryLabelRes(r.category))

    /** Retour à l'état initial, appelé dès que la session n'est plus ouverte. */
    fun reset() {
        screen = VaultScreen.Home
        backupStep = BackupStep.OVERVIEW
        backupNote = null
        newPhrase = null
        exportCancelled = true
        exportPhrase?.fill('\u0000')
        exportPhrase = null
        query = ""
        category = null
        draft = null
        error = null
        importCancelled = true
        closeViewer()
        captureFile?.delete()
        captureFile = null
        thumbs.clear()
        synchronized(thumbsRequested) { thumbsRequested.clear() }
    }

    fun load() {
        if (state.loaded || busy) return
        io {
            val loaded = c.vault.loadAll()
            val ids = loaded.records.map { it.id }.toSet()
            // Pièces d'une fiche jamais enregistrée (scan abandonné, verrouillage en cours de saisie).
            val (kept, orphans) = c.attachments.loadAll().partition { it.recordId in ids }
            orphans.forEach { c.attachments.delete(it) }
            state.set(loaded.records, loaded.unreadable, ::categoryLabel)
            state.attachments = kept
            refreshBackupInfo()
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
        when (screen) {
            VaultScreen.Home -> Unit
            VaultScreen.List, VaultScreen.PickCategory -> screen = VaultScreen.Home
            is VaultScreen.Detail -> screen = if (detailCameFromList) VaultScreen.List else VaultScreen.Home
            is VaultScreen.Viewer -> {
                closeViewer()
                screen = viewerReturn
            }
            VaultScreen.Backup -> {
                if (exportProgress != null) return
                backupNote = null
                if (backupStep != BackupStep.OVERVIEW) {
                    // La phrase affichée mais non confirmée est abandonnée.
                    newPhrase = null
                    backupStep = BackupStep.OVERVIEW
                } else {
                    screen = VaultScreen.Home
                }
            }
            VaultScreen.Edit -> cancelDraft()
        }
    }

    /** Abandonne la saisie ; pour une fiche neuve, les pièces déjà scannées sont supprimées. */
    private fun cancelDraft() {
        val d = draft ?: run {
            screen = VaultScreen.Home
            return
        }
        draft = null
        if (d.isNew) {
            screen = VaultScreen.Home
            val pending = attachmentsOf(d.id)
            if (pending.isNotEmpty()) io {
                pending.forEach { a ->
                    c.attachments.delete(a)
                    state.attachments = state.attachments.filterNot { it.id == a.id }
                }
            }
        } else {
            screen = VaultScreen.Detail(d.id)
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
        val d = Draft(Ids.new(), true, category, c.clock.wallMillis(), favorite = false, title = "", tags = "")
        fillTemplate(d, category, emptyMap())
        draft = d
        screen = VaultScreen.Edit
    }

    /** Remplit les champs du gabarit ; [keep] conserve les valeurs déjà saisies à la main. */
    private fun fillTemplate(d: Draft, category: Category, keep: Map<String, DraftField>) {
        val proposed = d.extraction?.valuesFor(category).orEmpty()
        val custom = d.fields.filter { it.custom }
        d.fields.clear()
        for (spec in Templates.fieldsFor(category)) {
            val previous = keep[spec.key]?.takeIf { it.value.isNotBlank() && !it.detected }
            val auto = proposed[spec.key]
            val value = previous?.value ?: auto?.let { if (spec.type == FieldType.DATE) DateText.toDisplay(it) else it } ?: ""
            d.fields += DraftField(spec.key, spec.type, false, spec.isExpiry, "", value, spec.sensitive, detected = previous == null && auto != null)
        }
        d.fields += custom
    }

    /** Change la catégorie d'une fiche neuve : les champs proposés suivent, la saisie manuelle est gardée. */
    fun changeDraftCategory(category: Category) {
        val d = draft ?: return
        if (!d.isNew || d.category == category) return
        val keep = d.fields.filter { !it.custom }.associateBy { it.key }
        d.category = category
        fillTemplate(d, category, keep)
    }

    fun edit(record: Record) {
        val d = Draft(record.id, false, record.category, record.createdAtMs, record.favorite, record.title, record.tags.joinToString(", "))
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
        val record = Record(d.id, d.category, d.title.trim(), tags, d.favorite, fields, expiry, d.createdAtMs, now)
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
        val copy = record.copy(id = Ids.new(), title = record.title + string(R.string.copy_suffix), favorite = false, createdAtMs = now, updatedAtMs = now)
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

    private fun replaceInState(record: Record) {
        state.set(state.records.filterNot { it.id == record.id } + record, state.unreadable, ::categoryLabel)
    }

    // ------------------------------------------------------------ pièces jointes

    fun attachmentsOf(recordId: String): List<AttachmentMeta> = state.attachments.filter { it.recordId == recordId }

    fun canAttach(recordId: String): Boolean = attachmentsOf(recordId).size < Limits.MAX_ATTACHMENTS_PER_RECORD

    /** À appeler juste avant d'ouvrir le sélecteur de fichiers ou l'appareil photo. */
    fun beforeExternalActivity() {
        error = null
        c.timers.startGrace(c.clock.elapsedMillis())
    }

    private class Source(val name: String, val size: Long, val deleteAfter: File?, val open: () -> InputStream)

    private fun sourceOf(uri: Uri): Source {
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
        return Source(name, size, null) { resolver.openInputStream(uri) ?: throw IOException("Fichier inaccessible") }
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

    /** Photo de travail prise par l'appareil photo, ou null si la prise de vue a été abandonnée. */
    private fun takeCapture(success: Boolean): Source? {
        val file = captureFile ?: return null
        captureFile = null
        if (!success || file.length() == 0L) {
            file.delete()
            return null
        }
        return Source(string(R.string.attach_photo_name), file.length(), file) { file.inputStream() }
    }

    fun onCameraMissing() {
        captureFile?.delete()
        captureFile = null
        error = R.string.attach_camera_unavailable
    }

    fun importFrom(uri: Uri, recordId: String, role: AttachmentRole) = startImport(recordId, role, sourceOf(uri))

    fun onCaptureResult(success: Boolean, recordId: String, role: AttachmentRole) {
        startImport(recordId, role, takeCapture(success) ?: return)
    }

    private fun startImport(recordId: String, role: AttachmentRole, source: Source) {
        if (importJob?.isActive == true || scanning) {
            source.deleteAfter?.delete()
            return
        }
        val refusal = when {
            !canAttach(recordId) -> R.string.attach_too_many
            source.size > Limits.MAX_FILE_BYTES -> R.string.attach_too_large
            else -> null
        }
        if (refusal != null) {
            source.deleteAfter?.delete()
            error = refusal
            return
        }
        importCancelled = false
        importProgress = 0
        error = null
        importJob = viewModelScope.launch {
            error = try {
                withContext(Dispatchers.IO) {
                    val meta = source.open().use { input ->
                        c.attachments.import(recordId, role, source.name, input, source.size, { importCancelled }) { importProgress = it }
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
            } catch (e: Exception) {
                messageFor(e)
            } finally {
                source.deleteAfter?.delete()
                importProgress = null
            }
        }
    }

    @StringRes
    private fun messageFor(e: Exception): Int? = when (e) {
        is ImportException -> when (e.reason) {
            ImportException.Reason.UNSUPPORTED_FORMAT -> R.string.attach_unsupported
            ImportException.Reason.TOO_LARGE -> R.string.attach_too_large
            ImportException.Reason.CANCELLED -> null
        }
        is NoSpaceException -> R.string.attach_no_space
        is SecurityException -> R.string.attach_pdf_protected
        is UnreadableDocument -> R.string.attach_corrupted
        else -> R.string.attach_failed
    }

    fun cancelImport() {
        importCancelled = true
    }

    private class UnreadableDocument : IOException("Document illisible")

    /** Première page ou image, décodée avec des dimensions bornées. Lève une exception si le document ne s'ouvre pas. */
    private fun firstPage(bytes: ByteArray, format: FileFormat): Bitmap =
        if (format == FileFormat.PDF) {
            try {
                PdfSession(bytes, c.attachments.viewDir).use { if (it.pageCount < 1) throw UnreadableDocument() else it.render(0, 1600) }
            } catch (e: SecurityException) {
                throw e
            } catch (e: UnreadableDocument) {
                throw e
            } catch (e: Exception) {
                throw UnreadableDocument()
            }
        } else {
            ImageRenderer.decode(bytes) ?: throw UnreadableDocument()
        }

    /** Vérifie que la pièce importée s'ouvre réellement et prépare son aperçu ; renvoie le message d'erreur sinon. */
    @StringRes
    private fun validate(meta: AttachmentMeta): Int? {
        val bytes = c.attachments.readAll(meta)
        return try {
            c.attachments.putThumb(meta, Thumbnails.jpeg(firstPage(bytes, meta.format)))
            null
        } catch (e: Exception) {
            messageFor(e)
        } finally {
            bytes.wipe()
        }
    }

    fun deleteAttachment(meta: AttachmentMeta) = io {
        c.attachments.delete(meta)
        state.attachments = state.attachments.filterNot { it.id == meta.id }
        thumbs.remove(meta.id)
    }

    /** Demande l'aperçu d'une pièce ; il apparaît dans [thumbs] dès qu'il est prêt. */
    fun requestThumb(meta: AttachmentMeta) {
        synchronized(thumbsRequested) { if (!thumbsRequested.add(meta.id)) return }
        viewModelScope.launch(thumbDispatcher) {
            try {
                val jpeg = c.attachments.thumb(meta) ?: run {
                    // Pièce importée avant l'existence des aperçus : on le fabrique une fois.
                    val bytes = c.attachments.readAll(meta)
                    try {
                        Thumbnails.jpeg(firstPage(bytes, meta.format)).also { c.attachments.putThumb(meta, it) }
                    } finally {
                        bytes.wipe()
                    }
                }
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let { thumbs[meta.id] = it.asImageBitmap() }
            } catch (e: Exception) {
                // Pas d'aperçu : l'interface montre une icône à la place.
            } catch (e: OutOfMemoryError) {
                // Idem.
            }
        }
    }

    // ------------------------------------------------------------ scan et lecture automatique

    fun scanFromUri(uri: Uri) = startScan(sourceOf(uri))

    /** Résultat du scanner de documents : une image déjà cadrée, écrite dans le cache privé. */
    fun onScannerResult(uri: Uri?) {
        if (uri == null) return
        val file = uri.takeIf { it.scheme == "file" }?.path?.let(::File)
        if (file != null) {
            // Copie en clair temporaire : effacée dès qu'elle est chiffrée dans le coffre, ou en cas d'échec.
            startScan(Source(string(R.string.scan_file_name), file.length(), file) { file.inputStream() })
        } else {
            startScan(sourceOf(uri))
        }
    }

    fun onScanCapture(success: Boolean) {
        startScan(takeCapture(success) ?: return)
    }

    private fun readBounded(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > Limits.MAX_FILE_BYTES) throw ImportException(ImportException.Reason.TOO_LARGE)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Scan d'un document : le fichier est lu une fois, son texte est reconnu sur
     * l'appareil, la pièce est chiffrée, puis une fiche pré-remplie est proposée à la
     * relecture. Rien n'est enregistré comme fiche tant que l'utilisateur n'a pas validé.
     */
    private fun startScan(source: Source) {
        if (scanning || importJob?.isActive == true) {
            source.deleteAfter?.delete()
            return
        }
        scanning = true
        error = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bytes = source.open().use { readBounded(it) }
                    try {
                        val format = FileSignature.detect(bytes.copyOf(minOf(bytes.size, FileSignature.PROBE_BYTES)))
                            ?: throw ImportException(ImportException.Reason.UNSUPPORTED_FORMAT)
                        val page = firstPage(bytes, format)
                        // Photo : redressée et recadrée sur le document. PDF : gardé tel quel, première page lue.
                        var image = page
                        var stored = bytes
                        val lines = try {
                            if (format == FileFormat.PDF) {
                                TextReader.recognize(page).lines
                            } else {
                                val scan = ScanProcessor.process(page)
                                if (scan.changed) {
                                    image = scan.bitmap
                                    stored = Thumbnails.jpeg(scan.bitmap, maxSide = ImageRenderer.MAX_SIDE, quality = 92)
                                }
                                scan.lines
                            }
                        } catch (e: Exception) {
                            emptyList()
                        }
                        val extraction = DocumentReader.read(lines, today())
                        val category = extraction.category ?: Category.OTHER
                        val recordId = Ids.new()
                        val role = Templates.rolesFor(category).first()
                        val meta = try {
                            c.attachments.import(recordId, role, source.name, ByteArrayInputStream(stored), stored.size.toLong(), { false }) {}
                        } finally {
                            if (stored !== bytes) stored.wipe()
                        }
                        c.attachments.putThumb(meta, Thumbnails.jpeg(image))
                        state.attachments = state.attachments + meta

                        val d = Draft(recordId, true, category, c.clock.wallMillis(), false, extraction.docType.orEmpty(), "", extraction)
                        d.titleDetected = extraction.docType != null
                        fillTemplate(d, category, emptyMap())
                        if (category == Category.IDENTITY && extraction.birthDate != null) {
                            d.fields += DraftField(Ids.new(), FieldType.DATE, true, false, string(R.string.field_birth_date), DateText.format(extraction.birthDate!!), false, detected = true)
                        }
                        draft = d
                        screen = VaultScreen.Edit
                    } finally {
                        bytes.wipe()
                    }
                }
            } catch (e: Exception) {
                error = messageFor(e) ?: R.string.attach_failed
            } catch (e: OutOfMemoryError) {
                error = R.string.attach_too_large
            } finally {
                source.deleteAfter?.delete()
                scanning = false
            }
        }
    }

    // ------------------------------------------------------------ sauvegarde

    var backupStep by mutableStateOf(BackupStep.OVERVIEW)
        private set
    var hasRecoveryPhrase by mutableStateOf(false)
        private set
    var lastExportMs by mutableStateOf<Long?>(null)
        private set

    /** Phrase de récupération tout juste générée, affichée une seule fois ; jamais écrite sur le disque. */
    var newPhrase by mutableStateOf<String?>(null)
        private set

    /** Octets de pièces jointes exportés pendant un export en cours, null sinon. */
    var exportProgress by mutableStateOf<Long?>(null)
        private set
    var backupNote by mutableStateOf<BackupNote?>(null)
        private set
    private var exportPhrase: CharArray? = null

    @Volatile
    private var exportCancelled = false

    private fun refreshBackupInfo() {
        hasRecoveryPhrase = c.backup.hasRecoveryPhrase
        lastExportMs = c.backup.lastExportMs()
    }

    fun exportState(): ExportReminder.State = ExportReminder.state(lastExportMs, c.clock.wallMillis())

    fun lastExportText(): String? = lastExportMs?.let { DateText.format(Deadlines.today(it, ZoneId.systemDefault())) }

    fun exportFileName(): String = "mes-papiers-" + today() + ".mparch"

    fun openBackup() {
        error = null
        backupNote = null
        backupStep = BackupStep.OVERVIEW
        refreshBackupInfo()
        screen = VaultScreen.Backup
    }

    fun generatePhrase() {
        newPhrase = String(RecoveryPhrase.generate())
        backupNote = null
        backupStep = BackupStep.SHOW_PHRASE
    }

    fun phraseNoted() {
        backupNote = null
        backupStep = BackupStep.CONFIRM_PHRASE
    }

    /** La phrase n'est retenue que si l'utilisateur la ressaisit correctement. */
    fun confirmPhrase(typed: String) {
        val entered = RecoveryPhrase.normalize(typed.toCharArray())
        val expected = newPhrase?.let { RecoveryPhrase.normalize(it.toCharArray()) }
        if (entered == null || expected == null || !entered.contentEquals(expected)) {
            backupNote = BackupNote(string(R.string.backup_phrase_mismatch), true)
            return
        }
        io {
            c.backup.saveRecoveryPhrase(entered)
            entered.fill('\u0000')
            newPhrase = null
            hasRecoveryPhrase = true
            backupStep = BackupStep.OVERVIEW
            backupNote = BackupNote(string(R.string.backup_phrase_saved), false)
        }
    }

    /** Vérifie la phrase ressaisie ; vrai si l'export peut continuer par le choix de l'emplacement. */
    fun prepareExport(typed: String): Boolean {
        val entered = RecoveryPhrase.normalize(typed.toCharArray())
        val ok = entered != null && try {
            c.backup.checkRecoveryPhrase(entered)
        } catch (e: Exception) {
            false
        }
        if (entered == null || !ok) {
            backupNote = BackupNote(string(R.string.backup_phrase_wrong), true)
            return false
        }
        exportPhrase = entered
        backupNote = null
        beforeExternalActivity()
        return true
    }

    fun exportTo(uri: Uri?) {
        val phrase = exportPhrase
        exportPhrase = null
        if (uri == null || phrase == null) {
            phrase?.fill('\u0000')
            return
        }
        exportCancelled = false
        exportProgress = 0
        val resolver = c.appContext.contentResolver
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val out = resolver.openOutputStream(uri, "w") ?: throw IOException("Emplacement inaccessible")
                    c.backup.export(out.buffered(), phrase, { exportCancelled }) { exportProgress = it }
                }
                lastExportMs = c.backup.lastExportMs()
                val text = getApplication<Application>().getString(R.string.backup_done, result.records, result.attachments) +
                    if (result.unreadable > 0) " " + getApplication<Application>().getString(R.string.backup_done_unreadable, result.unreadable) else ""
                backupNote = BackupNote(text, result.unreadable > 0)
            } catch (e: Exception) {
                // Pas d'archive partielle laissée derrière soi.
                withContext(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
                backupNote = if (e is ExportCancelled) BackupNote(string(R.string.backup_cancelled), false) else BackupNote(string(R.string.backup_failed), true)
            } finally {
                phrase.fill('\u0000')
                exportProgress = null
            }
        }
    }

    fun cancelExport() {
        exportCancelled = true
    }

    // ------------------------------------------------------------ visionneuse

    fun openAttachment(meta: AttachmentMeta) {
        closeViewer()
        viewerReturn = screen
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
