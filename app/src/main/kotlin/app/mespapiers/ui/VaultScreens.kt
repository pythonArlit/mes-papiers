package app.mespapiers.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import app.mespapiers.ViewerState
import app.mespapiers.core.model.AttachmentMeta
import app.mespapiers.core.model.AttachmentRole
import app.mespapiers.core.model.Templates
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.mespapiers.DateText
import app.mespapiers.Draft
import app.mespapiers.DraftField
import app.mespapiers.R
import app.mespapiers.VaultScreen
import app.mespapiers.VaultViewModel
import app.mespapiers.categoryLabelRes
import app.mespapiers.core.docs.DeadlineState
import app.mespapiers.core.docs.Deadlines
import app.mespapiers.core.docs.FormatChecks
import app.mespapiers.core.model.Category
import app.mespapiers.core.model.FieldType
import app.mespapiers.core.model.Record
import app.mespapiers.fieldLabelRes
import java.time.LocalDate

private const val MAX_SHOWN = 100

@Composable
fun VaultRoot(vm: VaultViewModel, onLock: () -> Unit) {
    LaunchedEffect(Unit) { vm.load() }
    BackHandler(enabled = vm.screen != VaultScreen.Home) { vm.back() }
    when (val s = vm.screen) {
        VaultScreen.Home -> HomeScreen(vm, onLock)
        VaultScreen.List -> ListScreen(vm)
        is VaultScreen.Detail -> {
            val record = vm.state.records.firstOrNull { it.id == s.id }
            if (record == null) LaunchedEffect(s.id) { vm.back() } else DetailScreen(vm, record)
        }
        VaultScreen.PickCategory -> PickCategoryScreen(vm)
        VaultScreen.Edit -> vm.draft?.let { EditScreen(vm, it) }
        is VaultScreen.Viewer -> ViewerScreen(vm)
    }
}

@Composable
private fun ErrorLine(vm: VaultViewModel) {
    val res = vm.error ?: return
    Text(
        stringResource(res),
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.semantics { heading() }.padding(top = 8.dp), style = MaterialTheme.typography.titleLarge)
}

/** État d'échéance en toutes lettres : l'information ne repose jamais sur la seule couleur. */
@Composable
private fun deadlineText(expiry: LocalDate?, today: LocalDate): Pair<String, Boolean>? {
    val status = Deadlines.status(expiry, today)
    val days = status.daysLeft?.toInt() ?: return null
    return when (status.state) {
        DeadlineState.EXPIRED -> pluralStringResource(R.plurals.deadline_expired, -days, -days) to true
        DeadlineState.SOON -> (if (days == 0) stringResource(R.string.deadline_today) else pluralStringResource(R.plurals.deadline_soon, days, days)) to false
        DeadlineState.VALID -> stringResource(R.string.deadline_valid, DateText.format(expiry!!)) to false
        DeadlineState.NONE -> null
    }
}

@Composable
private fun RecordCard(record: Record, today: LocalDate, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text((if (record.favorite) "★ " else "") + record.title, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(categoryLabelRes(record.category)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            deadlineText(record.expiry, today)?.let { (text, expired) ->
                Text(text, style = MaterialTheme.typography.bodyMedium, color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

// ------------------------------------------------------------ accueil

@Composable
private fun HomeScreen(vm: VaultViewModel, onLock: () -> Unit) {
    val today = vm.today()
    val records = vm.state.records
    Brand()
    WideButton(stringResource(R.string.home_add)) { vm.newDocument() }
    ErrorLine(vm)
    if (!vm.state.loaded) {
        BusyRow()
        return
    }
    if (vm.state.unreadable > 0) {
        InfoCard(stringResource(R.string.home_unreadable_title), pluralStringResource(R.plurals.home_unreadable, vm.state.unreadable, vm.state.unreadable), warning = true)
    }
    Text(
        if (records.isEmpty()) stringResource(R.string.home_count_none) else pluralStringResource(R.plurals.home_count, records.size, records.size),
        style = MaterialTheme.typography.titleMedium,
    )
    if (records.isEmpty()) {
        InfoCard(stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_body))
    } else {
        val due = records.filter { Deadlines.status(it.expiry, today).state.let { s -> s == DeadlineState.EXPIRED || s == DeadlineState.SOON } }.sortedBy { it.expiry }
        SectionTitle(stringResource(R.string.home_deadlines))
        if (due.isEmpty()) Text(stringResource(R.string.home_deadlines_none), style = MaterialTheme.typography.bodyLarge)
        due.take(MAX_SHOWN).forEach { r -> RecordCard(r, today) { vm.open(r.id) } }

        val favorites = records.filter { it.favorite }
        SectionTitle(stringResource(R.string.home_favorites))
        if (favorites.isEmpty()) Text(stringResource(R.string.home_favorites_none), style = MaterialTheme.typography.bodyLarge)
        favorites.take(MAX_SHOWN).forEach { r -> RecordCard(r, today) { vm.open(r.id) } }

        WideOutlinedButton(stringResource(R.string.home_all)) { vm.showList() }
    }
    WideOutlinedButton(stringResource(R.string.home_lock)) { onLock() }
}

// ------------------------------------------------------------ liste

@Composable
private fun ListScreen(vm: VaultViewModel) {
    val today = vm.today()
    Title(stringResource(R.string.list_title))
    OutlinedTextField(
        value = vm.query,
        onValueChange = { vm.query = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.list_search)) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
    )
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = vm.category == null, onClick = { vm.category = null }, label = { Text(stringResource(R.string.list_all_categories)) })
        Category.entries.forEach { c ->
            FilterChip(selected = vm.category == c, onClick = { vm.category = c }, label = { Text(stringResource(categoryLabelRes(c))) })
        }
    }
    val results = vm.results()
    when {
        vm.state.records.isEmpty() -> InfoCard(stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_body))
        results.isEmpty() -> InfoCard(stringResource(R.string.list_no_result_title), stringResource(R.string.list_no_result_body))
        else -> {
            Text(pluralStringResource(R.plurals.list_results, results.size, results.size), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
            results.take(MAX_SHOWN).forEach { r -> RecordCard(r, today) { vm.open(r.id) } }
            if (results.size > MAX_SHOWN) Text(stringResource(R.string.list_truncated, MAX_SHOWN), style = MaterialTheme.typography.bodyMedium)
        }
    }
    WideOutlinedButton(stringResource(R.string.action_back)) { vm.back() }
}

// ------------------------------------------------------------ détail

@Composable
private fun DetailScreen(vm: VaultViewModel, record: Record) {
    val today = vm.today()
    // Champs sensibles dévoilés à la demande ; l'état disparaît au verrouillage et en quittant la fiche.
    val revealed = remember(record.id) { mutableStateMapOf<String, Boolean>() }
    var confirmDelete by remember(record.id) { mutableStateOf(false) }
    var askDuplicate by remember(record.id) { mutableStateOf(false) }
    val attachments = vm.attachmentsOf(record.id)

    Title(record.title)
    Text(stringResource(categoryLabelRes(record.category)), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    deadlineText(record.expiry, today)?.let { (text, expired) ->
        InfoCard(stringResource(if (expired) R.string.detail_expired_title else R.string.detail_deadline_title), text, warning = expired)
    }
    if (record.tags.isNotEmpty()) Text(stringResource(R.string.detail_tags, record.tags.joinToString(", ")), style = MaterialTheme.typography.bodyMedium)
    ErrorLine(vm)

    if (record.fields.isEmpty()) Text(stringResource(R.string.detail_no_field), style = MaterialTheme.typography.bodyLarge)
    record.fields.forEach { f ->
        val label = if (f.custom) f.label else stringResource(fieldLabelRes(f.key))
        val shown = !f.sensitive || revealed[f.key] == true
        val value = if (f.type == FieldType.DATE) DateText.toDisplay(f.value) else f.value
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (shown) {
                    SelectionContainer {
                        Text(value, style = if (f.type == FieldType.IDENTIFIER) MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    Text(stringResource(R.string.detail_masked), style = MaterialTheme.typography.bodyLarge)
                }
                if (f.sensitive) {
                    TextButton(onClick = { revealed[f.key] = !shown }) {
                        Text(stringResource(if (shown) R.string.action_hide else R.string.action_show))
                    }
                }
            }
        }
    }

    AttachmentsSection(vm, record, attachments)

    WideButton(stringResource(R.string.detail_edit), enabled = !vm.busy) { vm.edit(record) }
    WideOutlinedButton(stringResource(if (record.favorite) R.string.detail_unfavorite else R.string.detail_favorite), enabled = !vm.busy) { vm.toggleFavorite(record) }
    WideOutlinedButton(stringResource(R.string.detail_duplicate), enabled = !vm.busy) {
        if (attachments.isEmpty()) vm.duplicate(record, copyAttachments = false) else askDuplicate = true
    }
    WideOutlinedButton(stringResource(R.string.detail_delete), enabled = !vm.busy) { confirmDelete = true }
    WideOutlinedButton(stringResource(R.string.action_back)) { vm.back() }

    if (askDuplicate) {
        AlertDialog(
            onDismissRequest = { askDuplicate = false },
            title = { Text(stringResource(R.string.duplicate_title)) },
            text = { Text(pluralStringResource(R.plurals.duplicate_body, attachments.size, attachments.size)) },
            confirmButton = {
                TextButton(onClick = {
                    askDuplicate = false
                    vm.duplicate(record, copyAttachments = true)
                }) { Text(stringResource(R.string.duplicate_with)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    askDuplicate = false
                    vm.duplicate(record, copyAttachments = false)
                }) { Text(stringResource(R.string.duplicate_without)) }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_body, record.title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(record)
                }) { Text(stringResource(R.string.delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

// ------------------------------------------------------------ ajout et modification

@Composable
private fun PickCategoryScreen(vm: VaultViewModel) {
    Title(stringResource(R.string.pick_title))
    Text(stringResource(R.string.pick_body), style = MaterialTheme.typography.bodyLarge)
    Category.entries.forEach { c -> WideOutlinedButton(stringResource(categoryLabelRes(c))) { vm.startDraft(c) } }
    WideOutlinedButton(stringResource(R.string.action_cancel)) { vm.back() }
}

@Composable
private fun EditScreen(vm: VaultViewModel, draft: Draft) {
    Title(stringResource(if (draft.id == null) R.string.edit_new_title else R.string.edit_title))
    Text(stringResource(categoryLabelRes(draft.category)), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.edit_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    OutlinedTextField(
        value = draft.title,
        onValueChange = { draft.title = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.edit_title_field)) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
    )
    draft.fields.filter { !it.custom }.forEach { f -> FieldEditor(f, stringResource(fieldLabelRes(f.key))) }

    OutlinedTextField(
        value = draft.tags,
        onValueChange = { draft.tags = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.edit_tags_field)) },
        supportingText = { Text(stringResource(R.string.edit_tags_hint)) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
    )

    val custom = draft.fields.filter { it.custom }
    if (custom.isNotEmpty()) SectionTitle(stringResource(R.string.edit_custom_section))
    custom.forEach { f -> CustomFieldEditor(vm, f) }
    WideOutlinedButton(stringResource(R.string.edit_add_field), enabled = !vm.busy) { vm.addCustomField() }

    ErrorLine(vm)
    if (vm.busy) BusyRow()
    WideButton(stringResource(R.string.edit_save), enabled = !vm.busy) { vm.save() }
    WideOutlinedButton(stringResource(R.string.action_cancel), enabled = !vm.busy) { vm.back() }
}

@Composable
private fun FieldEditor(f: DraftField, label: String) {
    // Champs sensibles et identifiants : pas de correction ni d'apprentissage par le clavier.
    val keyboard = when {
        f.type == FieldType.DATE -> KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false)
        f.sensitive -> KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None)
        f.type == FieldType.IDENTIFIER -> KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, capitalization = KeyboardCapitalization.Characters)
        else -> KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
    }
    val warning = when {
        f.type == FieldType.DATE && f.value.isNotBlank() && DateText.toIso(f.value) == null -> R.string.warn_date
        f.key == "iban" -> when (FormatChecks.iban(f.value)) {
            FormatChecks.Warning.IBAN_FORMAT -> R.string.warn_iban_format
            FormatChecks.Warning.IBAN_CHECKSUM -> R.string.warn_iban_checksum
            else -> null
        }
        f.key == "rib" && FormatChecks.rib(f.value) != null -> R.string.warn_rib
        else -> null
    }
    val placeholder: (@Composable () -> Unit)? =
        if (f.type == FieldType.DATE) { { Text(stringResource(R.string.edit_date_placeholder)) } } else null
    // Avertissement seulement : la saisie n'est jamais bloquée par un contrôle de format.
    val supporting: (@Composable () -> Unit)? =
        if (warning != null) { { Text(stringResource(warning)) } } else null
    OutlinedTextField(
        value = f.value,
        onValueChange = { f.value = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder,
        supportingText = supporting,
        singleLine = f.type != FieldType.TEXT,
        keyboardOptions = keyboard,
        shape = RoundedCornerShape(16.dp),
    )
}

@Composable
private fun CustomFieldEditor(vm: VaultViewModel, f: DraftField) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = f.label,
                onValueChange = { f.label = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.edit_custom_label)) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(FieldType.TEXT to R.string.type_text, FieldType.DATE to R.string.type_date, FieldType.IDENTIFIER to R.string.type_identifier).forEach { (type, res) ->
                    FilterChip(selected = f.type == type, onClick = { f.type = type }, label = { Text(stringResource(res)) })
                }
            }
            FieldEditor(f, stringResource(R.string.edit_custom_value))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.edit_custom_sensitive), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = f.sensitive, onCheckedChange = { f.sensitive = it })
            }
            TextButton(onClick = { vm.removeField(f) }) { Text(stringResource(R.string.edit_remove_field)) }
        }
    }
}

// ------------------------------------------------------------ pièces jointes

@StringRes
private fun roleLabelRes(role: AttachmentRole): Int = when (role) {
    AttachmentRole.FRONT -> R.string.role_front
    AttachmentRole.BACK -> R.string.role_back
    AttachmentRole.DOCUMENT -> R.string.role_document
    AttachmentRole.PROOF -> R.string.role_proof
    AttachmentRole.OTHER -> R.string.role_other
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1024 * 1024) "%.1f Mo".format(bytes / (1024.0 * 1024.0)) else "%d Ko".format((bytes + 1023) / 1024)

@Composable
private fun AttachmentsSection(vm: VaultViewModel, record: Record, attachments: List<AttachmentMeta>) {
    val suggested = Templates.rolesFor(record.category)
    val roles = (suggested + AttachmentRole.entries).distinct()
    // Rôle proposé : le premier rôle suggéré qui n'est pas encore utilisé.
    var role by remember(record.id, attachments.size) {
        mutableStateOf(suggested.firstOrNull { r -> attachments.none { it.role == r } } ?: AttachmentRole.OTHER)
    }
    var toDelete by remember(record.id) { mutableStateOf<AttachmentMeta?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFrom(uri, record.id, role)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        vm.onCaptureResult(success, record.id, role)
    }

    SectionTitle(stringResource(R.string.attach_title))
    if (attachments.isEmpty()) Text(stringResource(R.string.attach_none), style = MaterialTheme.typography.bodyLarge)
    attachments.forEach { a ->
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(stringResource(roleLabelRes(a.role)), style = MaterialTheme.typography.titleMedium)
                Text(
                    listOf(a.originalName, a.format.extension.uppercase(), formatSize(a.plainSize)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.openAttachment(a) }, enabled = !vm.busy) { Text(stringResource(R.string.attach_open)) }
                    TextButton(onClick = { toDelete = a }, enabled = !vm.busy) { Text(stringResource(R.string.attach_delete), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    val progress = vm.importProgress
    if (progress != null) {
        BusyRow(stringResource(R.string.attach_importing, formatSize(progress)))
        WideOutlinedButton(stringResource(R.string.attach_cancel)) { vm.cancelImport() }
    } else if (vm.canAttach(record.id)) {
        Text(stringResource(R.string.attach_role_prompt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            roles.forEach { r -> FilterChip(selected = role == r, onClick = { role = r }, label = { Text(stringResource(roleLabelRes(r))) }) }
        }
        WideOutlinedButton(stringResource(R.string.attach_pick), enabled = !vm.busy) {
            vm.beforeExternalActivity()
            try {
                picker.launch(arrayOf("image/jpeg", "image/png", "application/pdf"))
            } catch (e: ActivityNotFoundException) {
                vm.onCameraMissing()
            }
        }
        WideOutlinedButton(stringResource(R.string.attach_photo), enabled = !vm.busy) {
            val uri = vm.prepareCapture()
            if (uri != null) {
                try {
                    camera.launch(uri)
                } catch (e: ActivityNotFoundException) {
                    vm.onCameraMissing()
                }
            }
        }
        Text(stringResource(R.string.attach_source_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text(stringResource(R.string.attach_too_many), style = MaterialTheme.typography.bodyMedium)
    }

    toDelete?.let { a ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.attach_delete_title)) },
            text = { Text(stringResource(R.string.attach_delete_body, stringResource(roleLabelRes(a.role)))) },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    vm.deleteAttachment(a)
                }) { Text(stringResource(R.string.delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ViewerScreen(vm: VaultViewModel) {
    when (val v = vm.viewer) {
        ViewerState.Loading -> BusyRow()
        is ViewerState.Failed -> InfoCard(stringResource(R.string.viewer_title), stringResource(v.message), warning = true)
        is ViewerState.Picture -> ZoomableImage(v.bitmap, stringResource(R.string.viewer_image_description))
        is ViewerState.PdfPage -> {
            Text(stringResource(R.string.viewer_page, v.index + 1, v.count), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleMedium)
            ZoomableImage(v.bitmap, stringResource(R.string.viewer_page, v.index + 1, v.count))
            if (v.count > 1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.showPdfPage(v.index - 1) }, enabled = v.index > 0, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.viewer_previous))
                    }
                    TextButton(onClick = { vm.showPdfPage(v.index + 1) }, enabled = v.index < v.count - 1, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.viewer_next))
                    }
                }
            }
        }
    }
    WideOutlinedButton(stringResource(R.string.action_back)) { vm.back() }
}

/** Image agrandissable par pincement, déplaçable une fois agrandie. */
@Composable
private fun ZoomableImage(bitmap: android.graphics.Bitmap, description: String) {
    var scale by remember(bitmap) { mutableStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(Modifier.fillMaxWidth().clipToBounds().background(Color.White)) {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = description,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                .transformable(transform, canPan = { scale > 1f }),
            contentScale = ContentScale.FillWidth,
        )
    }
}
