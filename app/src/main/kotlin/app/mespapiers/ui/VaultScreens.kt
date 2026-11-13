package app.mespapiers.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.result.IntentSenderRequest
import androidx.compose.ui.platform.LocalContext
import app.mespapiers.platform.DocumentScanner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.WarningAmber
import app.mespapiers.BackupStep
import app.mespapiers.core.docs.ExportReminder
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mespapiers.DateText
import app.mespapiers.Draft
import app.mespapiers.DraftField
import app.mespapiers.R
import app.mespapiers.VaultScreen
import app.mespapiers.VaultViewModel
import app.mespapiers.ViewerState
import app.mespapiers.categoryLabelRes
import app.mespapiers.core.docs.DeadlineState
import app.mespapiers.core.docs.Deadlines
import app.mespapiers.core.docs.FormatChecks
import app.mespapiers.core.model.AttachmentMeta
import app.mespapiers.core.model.AttachmentRole
import app.mespapiers.core.model.Category
import app.mespapiers.core.model.FieldType
import app.mespapiers.core.model.FileFormat
import app.mespapiers.core.model.Record
import app.mespapiers.core.model.Templates
import app.mespapiers.fieldLabelRes
import java.time.LocalDate

private const val MAX_SHOWN = 100
private val CardShape = RoundedCornerShape(16.dp)
private val SCAN_TYPES = arrayOf("image/jpeg", "image/png", "application/pdf")

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
        is VaultScreen.Viewer -> ViewerScreen(vm, s.attachmentId)
        VaultScreen.Backup -> BackupScreen(vm)
    }
}

// ------------------------------------------------------------ briques

/** Gabarit d'écran : barre de titre, contenu défilant centré, barre d'action fixe en bas. */
@Composable
private fun ScreenFrame(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
            } else {
                Spacer(Modifier.width(12.dp))
                LogoMark(34.dp, onDark = false)
                Spacer(Modifier.width(12.dp))
            }
            Text(
                title,
                Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            actions()
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
        if (bottomBar != null) {
            Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.widthIn(max = 640.dp)) { bottomBar() }
                }
            }
        }
    }
}

@Composable
private fun ErrorLine(vm: VaultViewModel) {
    val res = vm.error ?: return
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.errorContainer) {
        Text(
            stringResource(res),
            Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Assertive },
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.semantics { heading() }.padding(top = 14.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        content = content,
    )
}

@Composable
private fun Pill(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(50), color = container) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = content)
    }
}

@Composable
private fun ActionButton(text: String, icon: ImageVector, primary: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
    if (primary) {
        Button(onClick, modifier.heightIn(min = 52.dp), enabled = enabled, shape = CardShape, content = content)
    } else {
        OutlinedButton(onClick, modifier.heightIn(min = 52.dp), enabled = enabled, shape = CardShape, content = content)
    }
}

private fun categoryIcon(category: Category): ImageVector = when (category) {
    Category.IDENTITY -> Icons.Filled.Badge
    Category.DIPLOMA -> Icons.Filled.School
    Category.CIVIL_STATUS -> Icons.Filled.FamilyRestroom
    Category.ADDRESS -> Icons.Filled.Home
    Category.BANK -> Icons.Filled.AccountBalance
    Category.OTHER -> Icons.Filled.Description
}

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

/** Aperçu déchiffré d'une pièce, chargé à la demande ; null tant qu'il n'est pas prêt. */
@Composable
private fun thumbOf(vm: VaultViewModel, meta: AttachmentMeta?): ImageBitmap? {
    if (meta == null) return null
    LaunchedEffect(meta.id) { vm.requestThumb(meta) }
    return vm.thumbs[meta.id]
}

/** Vignette d'une fiche : l'image du document s'il y en a une, sinon l'icône de sa catégorie. */
@Composable
private fun DocThumb(vm: VaultViewModel, record: Record, modifier: Modifier = Modifier) {
    val bitmap = thumbOf(vm, vm.attachmentsOf(record.id).firstOrNull())
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(categoryIcon(record.category), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** État d'échéance en toutes lettres : l'information ne repose jamais sur la seule couleur. */
@Composable
private fun deadlineText(expiry: LocalDate?, today: LocalDate): Pair<String, DeadlineState>? {
    val status = Deadlines.status(expiry, today)
    val days = status.daysLeft?.toInt() ?: return null
    val text = when (status.state) {
        DeadlineState.EXPIRED -> pluralStringResource(R.plurals.deadline_expired, -days, -days)
        DeadlineState.SOON -> if (days == 0) stringResource(R.string.deadline_today) else pluralStringResource(R.plurals.deadline_soon, days, days)
        DeadlineState.VALID -> stringResource(R.string.deadline_valid, DateText.format(expiry!!))
        DeadlineState.NONE -> return null
    }
    return text to status.state
}

@Composable
private fun DeadlinePill(expiry: LocalDate?, today: LocalDate) {
    val (text, state) = deadlineText(expiry, today) ?: return
    when (state) {
        DeadlineState.EXPIRED -> Pill(text, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        DeadlineState.SOON -> Pill(text, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        else -> Pill(text, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecordCard(vm: VaultViewModel, record: Record, today: LocalDate) {
    Card(
        onClick = { vm.open(record.id) },
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DocThumb(vm, record, Modifier.size(64.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (record.favorite) Icon(Icons.Filled.Star, contentDescription = stringResource(R.string.home_favorites), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(record.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(stringResource(categoryLabelRes(record.category)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DeadlinePill(record.expiry, today)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------ accueil

@Composable
private fun HomeScreen(vm: VaultViewModel, onLock: () -> Unit) {
    val today = vm.today()
    val records = vm.state.records
    var chooseSource by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.scanFromUri(uri) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success -> vm.onScanCapture(success) }
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.onScannerResult(if (result.resultCode == Activity.RESULT_OK) DocumentScanner.resultUri(result.data) else null)
    }
    val activity = DocumentScanner.activityOf(LocalContext.current)

    // Repli quand le scanner de documents n'est pas disponible sur ce téléphone.
    val systemCamera = {
        val uri = vm.prepareCapture()
        if (uri != null) {
            try {
                camera.launch(uri)
            } catch (e: ActivityNotFoundException) {
                vm.onCameraMissing()
            }
        }
    }
    val launchScanner = {
        vm.beforeExternalActivity()
        if (activity == null) {
            systemCamera()
        } else {
            DocumentScanner.start(
                activity,
                onReady = { sender ->
                    try {
                        scanner.launch(IntentSenderRequest.Builder(sender).build())
                    } catch (e: Exception) {
                        systemCamera()
                    }
                },
                onUnavailable = { systemCamera() },
            )
        }
    }

    ScreenFrame(
        title = stringResource(R.string.brand_name),
        onBack = null,
        actions = {
            IconButton(onClick = { vm.openBackup() }) { Icon(Icons.Filled.Archive, contentDescription = stringResource(R.string.backup_title)) }
            FilledTonalIconButton(onClick = onLock) { Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.home_lock)) }
            Spacer(Modifier.width(8.dp))
        },
    ) {
        // Carte d'action principale, aux couleurs de la marque.
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(BrandGradient)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.DocumentScanner, contentDescription = null, tint = Color.White)
                    }
                    Text(stringResource(R.string.home_add), style = MaterialTheme.typography.titleLarge, color = Color.White)
                }
                if (vm.scanning) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
                        Text(stringResource(R.string.scan_reading), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                    }
                } else {
                    Text(stringResource(R.string.scan_pitch), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
                    Button(
                        onClick = { chooseSource = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Petrol),
                    ) {
                        Icon(Icons.Filled.DocumentScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.scan_action), style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(onClick = { vm.newDocument() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                        Text(stringResource(R.string.scan_manual), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
        ErrorLine(vm)

        if (!vm.state.loaded) {
            BusyRow()
        } else {
            if (vm.exportState() != ExportReminder.State.RECENT) BackupReminder(vm)
            if (vm.state.unreadable > 0) {
                InfoCard(stringResource(R.string.home_unreadable_title), pluralStringResource(R.plurals.home_unreadable, vm.state.unreadable, vm.state.unreadable), warning = true)
            }
            val due = records.filter { Deadlines.status(it.expiry, today).state.let { s -> s == DeadlineState.EXPIRED || s == DeadlineState.SOON } }.sortedBy { it.expiry }
            val favorites = records.filter { it.favorite }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(records.size.toString(), stringResource(R.string.stat_documents), Icons.Filled.Description, Modifier.weight(1f))
                StatTile(favorites.size.toString(), stringResource(R.string.stat_favorites), Icons.Filled.Star, Modifier.weight(1f))
                StatTile(due.size.toString(), stringResource(R.string.stat_deadlines), Icons.Filled.Event, Modifier.weight(1f), alert = due.isNotEmpty())
            }

            if (records.isEmpty()) {
                EmptyState(Icons.Filled.FileCopy, stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_body))
            } else {
                SectionTitle(stringResource(R.string.home_deadlines))
                if (due.isEmpty()) Text(stringResource(R.string.home_deadlines_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                due.take(MAX_SHOWN).forEach { RecordCard(vm, it, today) }

                SectionTitle(stringResource(R.string.home_favorites))
                if (favorites.isEmpty()) Text(stringResource(R.string.home_favorites_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                favorites.take(MAX_SHOWN).forEach { RecordCard(vm, it, today) }

                Spacer(Modifier.height(4.dp))
                ActionButton(pluralStringResource(R.plurals.home_all, records.size, records.size), Icons.Filled.Search, modifier = Modifier.fillMaxWidth()) { vm.showList() }
            }
        }
    }

    if (chooseSource) {
        AlertDialog(
            onDismissRequest = { chooseSource = false },
            title = { Text(stringResource(R.string.scan_action)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.scan_source_hint))
                    ActionButton(stringResource(R.string.scan_with_camera), Icons.Filled.DocumentScanner, primary = true, modifier = Modifier.fillMaxWidth()) {
                        chooseSource = false
                        launchScanner()
                    }
                    ActionButton(stringResource(R.string.scan_from_file), Icons.Filled.UploadFile, modifier = Modifier.fillMaxWidth()) {
                        chooseSource = false
                        vm.beforeExternalActivity()
                        try {
                            picker.launch(SCAN_TYPES)
                        } catch (e: ActivityNotFoundException) {
                            vm.onCameraMissing()
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { chooseSource = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun StatTile(value: String, label: String, icon: ImageVector, modifier: Modifier = Modifier, alert: Boolean = false) {
    val accent = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Panel(modifier) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = accent)
            }
            Text(value, style = MaterialTheme.typography.headlineSmall, color = accent)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** État vide : une icône, un titre, une phrase. */
@Composable
private fun EmptyState(icon: ImageVector, title: String, body: String) {
    Panel {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

// ------------------------------------------------------------ liste

@Composable
private fun ListScreen(vm: VaultViewModel) {
    val today = vm.today()
    ScreenFrame(stringResource(R.string.list_title), onBack = { vm.back() }) {
        OutlinedTextField(
            value = vm.query,
            onValueChange = { vm.query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.list_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = CardShape,
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = vm.category == null, onClick = { vm.category = null }, label = { Text(stringResource(R.string.list_all_categories)) })
            Category.entries.forEach { c ->
                FilterChip(selected = vm.category == c, onClick = { vm.category = c }, label = { Text(stringResource(categoryLabelRes(c))) })
            }
        }
        val results = vm.results()
        when {
            vm.state.records.isEmpty() -> EmptyState(Icons.Filled.FileCopy, stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_body))
            results.isEmpty() -> EmptyState(Icons.Filled.Search, stringResource(R.string.list_no_result_title), stringResource(R.string.list_no_result_body))
            else -> {
                Text(
                    pluralStringResource(R.plurals.list_results, results.size, results.size),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                results.take(MAX_SHOWN).forEach { RecordCard(vm, it, today) }
                if (results.size > MAX_SHOWN) Text(stringResource(R.string.list_truncated, MAX_SHOWN), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ------------------------------------------------------------ détail

/** Grand aperçu du document : un appui l'ouvre en plein écran. */
@Composable
private fun DocumentPreview(vm: VaultViewModel, meta: AttachmentMeta) {
    val bitmap = thumbOf(vm, meta)
    Card(
        onClick = { vm.openAttachment(meta) },
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 300.dp), contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(bitmap, contentDescription = stringResource(R.string.preview_open, stringResource(roleLabelRes(meta.role))), modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp), contentScale = ContentScale.Fit)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (meta.format == FileFormat.PDF) Icons.Filled.PictureAsPdf else Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.preview_open, stringResource(roleLabelRes(meta.role))), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun DetailScreen(vm: VaultViewModel, record: Record) {
    val today = vm.today()
    // Champs sensibles dévoilés à la demande ; l'état disparaît au verrouillage et en quittant la fiche.
    val revealed = remember(record.id) { mutableStateMapOf<String, Boolean>() }
    var confirmDelete by remember(record.id) { mutableStateOf(false) }
    var askDuplicate by remember(record.id) { mutableStateOf(false) }
    var menu by remember(record.id) { mutableStateOf(false) }
    val attachments = vm.attachmentsOf(record.id)

    ScreenFrame(
        title = stringResource(categoryLabelRes(record.category)),
        onBack = { vm.back() },
        actions = {
            IconButton(onClick = { vm.toggleFavorite(record) }, enabled = !vm.busy) {
                Icon(
                    if (record.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = stringResource(if (record.favorite) R.string.detail_unfavorite else R.string.detail_favorite),
                    tint = if (record.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { vm.edit(record) }, enabled = !vm.busy) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.detail_edit)) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.detail_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.detail_duplicate)) },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                        onClick = {
                            menu = false
                            if (attachments.isEmpty()) vm.duplicate(record, copyAttachments = false) else askDuplicate = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.detail_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menu = false
                            confirmDelete = true
                        },
                    )
                }
            }
        },
    ) {
        attachments.firstOrNull()?.let { DocumentPreview(vm, it) }

        Text(record.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DeadlinePill(record.expiry, today)
            record.tags.forEach { Pill(it, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer) }
        }
        ErrorLine(vm)

        SectionTitle(stringResource(R.string.detail_information))
        if (record.fields.isEmpty()) {
            Text(stringResource(R.string.detail_no_field), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Panel {
                record.fields.forEachIndexed { index, f ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    val label = if (f.custom) f.label else stringResource(fieldLabelRes(f.key))
                    val shown = !f.sensitive || revealed[f.key] == true
                    val value = if (f.type == FieldType.DATE) DateText.toDisplay(f.value) else f.value
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (shown) {
                                SelectionContainer {
                                    Text(value, style = if (f.type == FieldType.IDENTIFIER) MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge)
                                }
                            } else {
                                Text(stringResource(R.string.detail_masked), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        if (f.sensitive) {
                            IconButton(onClick = { revealed[f.key] = !shown }) {
                                Icon(if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = stringResource(if (shown) R.string.action_hide else R.string.action_show))
                            }
                        } else {
                            Spacer(Modifier.width(12.dp))
                        }
                    }
                }
            }
        }

        AttachmentsSection(vm, record, attachments)
    }

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

// ------------------------------------------------------------ pièces jointes

@Composable
private fun AttachmentsSection(vm: VaultViewModel, record: Record, attachments: List<AttachmentMeta>) {
    val suggested = Templates.rolesFor(record.category)
    val roles = (suggested + AttachmentRole.entries).distinct()
    // Rôle proposé : le premier rôle suggéré qui n'est pas encore utilisé.
    var role by remember(record.id, attachments.size) {
        mutableStateOf(suggested.firstOrNull { r -> attachments.none { it.role == r } } ?: AttachmentRole.OTHER)
    }
    var toDelete by remember(record.id) { mutableStateOf<AttachmentMeta?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importFrom(uri, record.id, role) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success -> vm.onCaptureResult(success, record.id, role) }

    SectionTitle(stringResource(R.string.attach_title))
    if (attachments.isEmpty()) {
        Text(stringResource(R.string.attach_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Panel {
            attachments.forEachIndexed { index, a ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Row(
                    Modifier.fillMaxWidth().clickable { vm.openAttachment(a) }.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val bitmap = thumbOf(vm, a)
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        if (bitmap != null) {
                            Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            Icon(if (a.format == FileFormat.PDF) Icons.Filled.PictureAsPdf else Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(roleLabelRes(a.role)), style = MaterialTheme.typography.titleSmall)
                        Text(
                            listOf(a.originalName, a.format.extension.uppercase(), formatSize(a.plainSize)).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { toDelete = a }, enabled = !vm.busy) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.attach_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(stringResource(R.string.attach_photo_short), Icons.Filled.PhotoCamera, enabled = !vm.busy, modifier = Modifier.weight(1f)) {
                val uri = vm.prepareCapture()
                if (uri != null) {
                    try {
                        camera.launch(uri)
                    } catch (e: ActivityNotFoundException) {
                        vm.onCameraMissing()
                    }
                }
            }
            ActionButton(stringResource(R.string.attach_pick_short), Icons.Filled.UploadFile, enabled = !vm.busy, modifier = Modifier.weight(1f)) {
                vm.beforeExternalActivity()
                try {
                    picker.launch(SCAN_TYPES)
                } catch (e: ActivityNotFoundException) {
                    vm.onCameraMissing()
                }
            }
        }
        Text(stringResource(R.string.attach_source_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

// ------------------------------------------------------------ visionneuse

@Composable
private fun ViewerScreen(vm: VaultViewModel, attachmentId: String) {
    val meta = vm.state.attachments.firstOrNull { it.id == attachmentId }
    val title = meta?.let { stringResource(roleLabelRes(it.role)) } ?: stringResource(R.string.viewer_image_description)
    val v = vm.viewer
    ScreenFrame(
        title = title,
        onBack = { vm.back() },
        bottomBar = if (v is ViewerState.PdfPage && v.count > 1) {
            {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { vm.showPdfPage(v.index - 1) }, enabled = v.index > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.viewer_previous)) }
                    Text(
                        stringResource(R.string.viewer_page, v.index + 1, v.count),
                        Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = { vm.showPdfPage(v.index + 1) }, enabled = v.index < v.count - 1, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.viewer_next)) }
                }
            }
        } else {
            null
        },
    ) {
        when (v) {
            ViewerState.Loading -> BusyRow()
            is ViewerState.Failed -> InfoCard(stringResource(R.string.viewer_title), stringResource(v.message), warning = true)
            is ViewerState.Picture -> ZoomableImage(v.bitmap, title)
            is ViewerState.PdfPage -> ZoomableImage(v.bitmap, stringResource(R.string.viewer_page, v.index + 1, v.count))
        }
        if (v is ViewerState.Picture || v is ViewerState.PdfPage) {
            Text(stringResource(R.string.viewer_zoom_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Le document tel qu'il est, agrandissable par pincement et déplaçable une fois agrandi. */
@Composable
private fun ZoomableImage(bitmap: android.graphics.Bitmap, description: String) {
    var scale by remember(bitmap) { mutableStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Surface(shape = RoundedCornerShape(8.dp), shadowElevation = 4.dp, color = Color.White) {
        Box(Modifier.fillMaxWidth().clipToBounds()) {
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
}

// ------------------------------------------------------------ ajout et modification

@Composable
private fun PickCategoryScreen(vm: VaultViewModel) {
    ScreenFrame(stringResource(R.string.pick_title), onBack = { vm.back() }) {
        Text(stringResource(R.string.pick_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Category.entries.forEach { c ->
            Card(
                onClick = { vm.startDraft(c) },
                modifier = Modifier.fillMaxWidth(),
                shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(categoryIcon(c), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text(stringResource(categoryLabelRes(c)), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EditScreen(vm: VaultViewModel, draft: Draft) {
    val scanned = draft.extraction != null
    ScreenFrame(
        title = stringResource(if (draft.isNew) R.string.edit_new_title else R.string.edit_title),
        onBack = { vm.back() },
        bottomBar = {
            Button(onClick = { vm.save() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !vm.busy, shape = CardShape) {
                Text(stringResource(R.string.edit_save), style = MaterialTheme.typography.titleMedium)
            }
        },
    ) {
        if (draft.isNew) vm.attachmentsOf(draft.id).firstOrNull()?.let { DocumentPreview(vm, it) }

        if (scanned) {
            val count = draft.detectedCount
            Surface(shape = CardShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(
                        if (count > 0) pluralStringResource(R.plurals.scan_detected, count, count) else stringResource(R.string.scan_nothing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        } else {
            Text(stringResource(R.string.edit_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (draft.isNew) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Category.entries.forEach { c ->
                    FilterChip(
                        selected = draft.category == c,
                        onClick = { vm.changeDraftCategory(c) },
                        label = { Text(stringResource(categoryLabelRes(c))) },
                        leadingIcon = { Icon(categoryIcon(c), contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
        } else {
            Text(stringResource(categoryLabelRes(draft.category)), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        OutlinedTextField(
            value = draft.title,
            onValueChange = {
                draft.title = it
                draft.titleDetected = false
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.edit_title_field)) },
            supportingText = detectedHint(draft.titleDetected),
            singleLine = true,
            shape = CardShape,
        )
        draft.fields.filter { !it.custom }.forEach { f -> FieldEditor(f, stringResource(fieldLabelRes(f.key))) }

        OutlinedTextField(
            value = draft.tags,
            onValueChange = { draft.tags = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.edit_tags_field)) },
            supportingText = { Text(stringResource(R.string.edit_tags_hint)) },
            singleLine = true,
            shape = CardShape,
        )

        val custom = draft.fields.filter { it.custom }
        if (custom.isNotEmpty()) SectionTitle(stringResource(R.string.edit_custom_section))
        custom.forEach { f -> CustomFieldEditor(vm, f) }
        ActionButton(stringResource(R.string.edit_add_field), Icons.Filled.Add, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { vm.addCustomField() }

        ErrorLine(vm)
        if (vm.busy) BusyRow()
    }
}

/** Mention affichée sous un champ rempli automatiquement, jusqu'à ce que l'utilisateur le modifie. */
@Composable
private fun detectedHint(detected: Boolean): (@Composable () -> Unit)? =
    if (!detected) null else {
        {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.scan_field_detected), color = MaterialTheme.colorScheme.primary)
            }
        }
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
        if (warning != null) { { Text(stringResource(warning)) } } else detectedHint(f.detected)
    OutlinedTextField(
        value = f.value,
        onValueChange = {
            f.value = it
            f.detected = false
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder,
        supportingText = supporting,
        singleLine = f.type != FieldType.TEXT,
        keyboardOptions = keyboard,
        shape = CardShape,
    )
}

@Composable
private fun CustomFieldEditor(vm: VaultViewModel, f: DraftField) {
    Panel {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = f.label,
                onValueChange = { f.label = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.edit_custom_label)) },
                singleLine = true,
                shape = CardShape,
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

// ------------------------------------------------------------ sauvegarde

/** Rappel affiché sur l'accueil quand il n'y a pas de sauvegarde, ou qu'elle date. */
@Composable
private fun BackupReminder(vm: VaultViewModel) {
    val never = vm.exportState() == ExportReminder.State.NEVER
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, end = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.WarningAmber, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(22.dp))
            Column {
                Text(stringResource(if (never) R.string.backup_reminder_never_title else R.string.backup_reminder_stale_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (never) stringResource(R.string.backup_reminder_never_body) else stringResource(R.string.backup_reminder_stale_body, vm.lastExportText().orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { vm.openBackup() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.backup_reminder_action)) }
            }
        }
    }
}

@Composable
private fun BackupNoteLine(vm: VaultViewModel) {
    val note = vm.backupNote ?: return
    Surface(shape = CardShape, color = if (note.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            note.text,
            Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Assertive },
            color = if (note.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun BackupScreen(vm: VaultViewModel) {
    var typed by remember(vm.backupStep) { mutableStateOf("") }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> vm.exportTo(uri) }
    ScreenFrame(stringResource(R.string.backup_title), onBack = { vm.back() }) {
        when (vm.backupStep) {
            BackupStep.SHOW_PHRASE -> {
                Text(stringResource(R.string.backup_phrase_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                InfoCard(stringResource(R.string.backup_note_title), stringResource(R.string.backup_note_body), warning = true)
                Panel {
                    // Non sélectionnable : la phrase ne doit pas passer par le presse-papiers.
                    Text(
                        vm.newPhrase.orEmpty().split('-').chunked(2).joinToString("\n") { it.joinToString("  ") },
                        Modifier.fillMaxWidth().padding(20.dp),
                        style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 40.sp),
                        textAlign = TextAlign.Center,
                    )
                }
                WideButton(stringResource(R.string.backup_noted)) { vm.phraseNoted() }
            }
            BackupStep.CONFIRM_PHRASE -> {
                Text(stringResource(R.string.backup_confirm_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.backup_confirm_body), style = MaterialTheme.typography.bodyLarge)
                RecoveryPhraseField(typed, { typed = it }, !vm.busy)
                BackupNoteLine(vm)
                if (vm.busy) BusyRow()
                WideButton(stringResource(R.string.backup_confirm_action), enabled = !vm.busy && typed.isNotBlank()) { vm.confirmPhrase(typed) }
            }
            BackupStep.OVERVIEW -> {
                Panel {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Column {
                            Text(stringResource(R.string.backup_last_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(vm.lastExportText() ?: stringResource(R.string.backup_last_never), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                InfoCard(stringResource(R.string.backup_why_title), stringResource(R.string.backup_why_body))
                BackupNoteLine(vm)
                val progress = vm.exportProgress
                when {
                    progress != null -> {
                        BusyRow(stringResource(R.string.backup_exporting, formatSize(progress)))
                        WideOutlinedButton(stringResource(R.string.backup_cancel)) { vm.cancelExport() }
                    }
                    !vm.hasRecoveryPhrase -> {
                        Text(stringResource(R.string.backup_no_phrase), style = MaterialTheme.typography.bodyLarge)
                        WideButton(stringResource(R.string.backup_create_phrase)) { vm.generatePhrase() }
                    }
                    else -> {
                        Text(stringResource(R.string.backup_enter_phrase), style = MaterialTheme.typography.bodyLarge)
                        RecoveryPhraseField(typed, { typed = it }, true)
                        WideButton(stringResource(R.string.backup_export), enabled = typed.isNotBlank()) {
                            if (vm.prepareExport(typed)) {
                                typed = ""
                                try {
                                    saver.launch(vm.exportFileName())
                                } catch (e: ActivityNotFoundException) {
                                    vm.exportTo(null)
                                    vm.onCameraMissing()
                                }
                            }
                        }
                        TextButton(onClick = { vm.generatePhrase() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.backup_new_phrase)) }
                    }
                }
            }
        }
    }
}
