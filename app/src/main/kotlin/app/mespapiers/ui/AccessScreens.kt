package app.mespapiers.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.mespapiers.AccessViewModel
import app.mespapiers.SecondFactor
import app.mespapiers.VaultViewModel
import androidx.compose.runtime.LaunchedEffect
import app.mespapiers.R
import app.mespapiers.Screen
import app.mespapiers.UiMessage
import app.mespapiers.Variant
import app.mespapiers.core.docs.PassphraseStrength
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

private val Petrol = Color(0xFF0F4C5C)
private val Mint = Color(0xFF5FD3A6)

// Contrastes calculés selon WCAG (AA : texte normal ≥ 4,5:1) :
// blanc sur bleu pétrole 9,5:1 ; bleu pétrole sur fond clair 8,9:1 ; vert très sombre sur menthe 9,3:1.
// Le vert menthe n'est jamais utilisé comme couleur de texte sur fond clair (1,7:1).
private val LightColors = lightColorScheme(
    primary = Petrol,
    onPrimary = Color.White,
    secondary = Mint,
    onSecondary = Color(0xFF00201A),
    background = Color(0xFFF4F8F8),
    onBackground = Color(0xFF14201F),
    surface = Color.White,
    onSurface = Color(0xFF14201F),
    surfaceVariant = Color(0xFFE1EBEB),
    onSurfaceVariant = Color(0xFF3B4A4A),
    primaryContainer = Color(0xFFCFE8EC),
    onPrimaryContainer = Color(0xFF06313B),
    secondaryContainer = Color(0xFFD5F4E7),
    onSecondaryContainer = Color(0xFF0B3B2B),
    tertiary = Color(0xFF7A5900),
    tertiaryContainer = Color(0xFFFFE9C2),
    onTertiaryContainer = Color(0xFF3E2A00),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF00201A),
    secondary = Mint,
    background = Color(0xFF0E1A1C),
    onBackground = Color(0xFFE1EBEB),
    surface = Color(0xFF16272A),
    onSurface = Color(0xFFE1EBEB),
    surfaceVariant = Color(0xFF22383C),
    onSurfaceVariant = Color(0xFFBCCBCB),
    primaryContainer = Color(0xFF14505E),
    onPrimaryContainer = Color(0xFFCFE8EC),
    secondaryContainer = Color(0xFF1D4A3A),
    onSecondaryContainer = Color(0xFFD5F4E7),
    tertiary = Color(0xFFF1C04C),
    tertiaryContainer = Color(0xFF5A4300),
    onTertiaryContainer = Color(0xFFFFE9C2),
    error = Color(0xFFF2B8B5),
)

@Composable
fun MesPapiersTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

@Composable
fun AppRoot(vm: AccessViewModel, vault: VaultViewModel) {
    MesPapiersTheme {
        Surface(
            Modifier
                .fillMaxSize()
                // Toute interaction repousse le verrouillage pour inactivité.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            vm.onInteraction()
                        }
                    }
                },
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.safeDrawingPadding()) {
                if (Variant.IS_DEMO) DemoBanner()
                if (vm.screen == Screen.Home) {
                    VaultRoot(vault, onLock = { vm.lock() })
                } else {
                    // Dès que la session n'est plus ouverte, plus rien du coffre ne reste à l'écran.
                    LaunchedEffect(Unit) { vault.reset() }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Column(
                            Modifier
                                .widthIn(max = 560.dp)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            when (val s = vm.screen) {
                                Screen.Loading -> BusyRow()
                                Screen.Intro -> IntroScreen(vm)
                                Screen.CreatePassphrase -> CreatePassphraseScreen(vm)
                                is Screen.Enroll -> EnrollScreen(vm, s)
                                Screen.Passphrase -> PassphraseScreen(vm)
                                Screen.Totp -> TotpScreen(vm)
                                Screen.Home -> Unit
                                Screen.Unreadable -> UnreadableScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DemoBanner() {
    Text(
        stringResource(R.string.demo_banner),
        Modifier.fillMaxWidth().background(Color(0xFFB3261E)).padding(4.dp),
        color = Color.White,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelLarge,
    )
}

// ------------------------------------------------------------ briques communes

@Composable
internal fun Brand() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.app_name),
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.app_subtitle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun Title(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
}

@Composable
internal fun InfoCard(title: String, body: String, warning: Boolean = false) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun WideButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled, shape = RoundedCornerShape(16.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun WideOutlinedButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled, shape = RoundedCornerShape(16.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun BusyRow(text: String = stringResource(R.string.working)) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator()
        Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

/** Erreur ou information, annoncée par TalkBack dès qu'elle apparaît. */
@Composable
internal fun MessageLine(message: UiMessage?) {
    if (message == null) return
    val text = if (message.arg != null) stringResource(message.res, message.arg) else stringResource(message.res)
    Text(
        text,
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyLarge,
    )
}

/** Champ secret : pas de correction, pas de suggestion, pas de majuscule automatique. */
@Composable
internal fun SecretField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean, imeAction: ImeAction, onDone: () -> Unit = {}) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(stringResource(if (visible) R.string.action_hide else R.string.action_show))
            }
        },
        shape = RoundedCornerShape(16.dp),
    )
}

@Composable
internal fun CodeField(value: String, onChange: (String) -> Unit, enabled: Boolean, onDone: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { new -> onChange(new.filter { it.isDigit() }.take(6)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.enroll_code_field)) },
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        shape = RoundedCornerShape(16.dp),
    )
}

internal fun formatDuration(ms: Long): String {
    val total = (ms + 999) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d h %02d min".format(h, m) else if (m > 0) "%d min %02d s".format(m, s) else "%d s".format(s)
}

// ------------------------------------------------------------ configuration

@Composable
internal fun IntroScreen(vm: AccessViewModel) {
    Brand()
    Title(stringResource(R.string.intro_title))
    Text(stringResource(R.string.intro_body), style = MaterialTheme.typography.bodyLarge)
    if (!vm.deviceSecure) InfoCard(stringResource(R.string.intro_no_lock_title), stringResource(R.string.intro_no_lock_body), warning = true)
    if (SecondFactor.AUTOMATIC) {
        InfoCard(stringResource(R.string.demo_single_step_title), stringResource(R.string.demo_single_step_body), warning = true)
    } else {
        InfoCard(stringResource(R.string.intro_two_steps_title), stringResource(R.string.intro_two_steps_body))
        InfoCard(stringResource(R.string.intro_no_reset_title), stringResource(R.string.intro_no_reset_body))
        InfoCard(stringResource(R.string.intro_same_device_title), stringResource(R.string.intro_same_device_body))
    }
    WideButton(stringResource(R.string.intro_start)) { vm.startSetup() }
}

@Composable
internal fun CreatePassphraseScreen(vm: AccessViewModel) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    Title(stringResource(R.string.create_title))
    Text(stringResource(R.string.create_body), style = MaterialTheme.typography.bodyLarge)
    SecretField(passphrase, { passphrase = it }, stringResource(R.string.create_field), !vm.busy, ImeAction.Next)
    if (passphrase.isNotEmpty()) {
        val level = when (PassphraseStrength.estimate(passphrase)) {
            PassphraseStrength.Level.TOO_SHORT -> R.string.strength_too_short
            PassphraseStrength.Level.WEAK -> R.string.strength_weak
            PassphraseStrength.Level.FAIR -> R.string.strength_fair
            PassphraseStrength.Level.GOOD -> R.string.strength_good
            PassphraseStrength.Level.STRONG -> R.string.strength_strong
        }
        Text(stringResource(R.string.strength_label, stringResource(level)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SecretField(confirmation, { confirmation = it }, stringResource(R.string.create_confirm_field), !vm.busy, ImeAction.Done) {
        vm.createPassphrase(passphrase, confirmation)
    }
    MessageLine(vm.message)
    if (vm.busy) BusyRow(stringResource(R.string.create_preparing))
    WideButton(stringResource(R.string.action_continue), enabled = !vm.busy) { vm.createPassphrase(passphrase, confirmation) }
}

/** QR généré localement (ZXing), sans aucun service distant. */
internal fun qrBitmap(content: String, size: Int = 720): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
internal fun EnrollScreen(vm: AccessViewModel, state: Screen.Enroll) {
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    val qr = remember(state.otpAuthUri) { qrBitmap(state.otpAuthUri).asImageBitmap() }
    Title(stringResource(R.string.enroll_title))
    Text(stringResource(R.string.enroll_body), style = MaterialTheme.typography.bodyLarge)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(
            qr,
            contentDescription = stringResource(R.string.enroll_qr_description),
            modifier = Modifier.size(240.dp).background(Color.White).padding(4.dp),
        )
    }
    WideOutlinedButton(stringResource(R.string.enroll_open_app), enabled = !vm.busy) {
        // Seule sortie de la graine hors de l'application, à la demande expresse de
        // l'utilisateur : un QR ne peut pas être scanné depuis le téléphone qui l'affiche.
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.otpAuthUri)))
        } catch (e: ActivityNotFoundException) {
            vm.onNoAuthenticatorApp()
        }
    }
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.enroll_manual_key), style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Text(state.manualKey, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
            }
            Text(stringResource(R.string.enroll_issuer_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    CodeField(code, { code = it }, !vm.busy) { vm.confirmEnrollment(code) }
    MessageLine(vm.message)
    Text(stringResource(R.string.enroll_warning), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (vm.busy) BusyRow()
    WideButton(stringResource(R.string.enroll_confirm), enabled = !vm.busy && code.length == 6) { vm.confirmEnrollment(code) }
    WideOutlinedButton(stringResource(R.string.action_back), enabled = !vm.busy) { vm.cancelEnrollment() }
}

// ------------------------------------------------------------ connexion

@Composable
internal fun PassphraseScreen(vm: AccessViewModel) {
    var passphrase by remember { mutableStateOf("") }
    val waiting = vm.waitMs > 0
    Brand()
    Title(stringResource(R.string.login_title))
    Text(stringResource(if (SecondFactor.AUTOMATIC) R.string.demo_login_step else R.string.login_step1), style = MaterialTheme.typography.bodyLarge)
    SecretField(passphrase, { passphrase = it }, stringResource(R.string.create_field), !vm.busy && !waiting, ImeAction.Done) {
        vm.submitPassphrase(passphrase)
        passphrase = ""
    }
    MessageLine(vm.message)
    if (waiting) {
        Text(
            stringResource(R.string.passphrase_wait, formatDuration(vm.waitMs)),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (vm.busy) BusyRow()
    WideButton(stringResource(R.string.login_unlock), enabled = !vm.busy && !waiting && passphrase.isNotEmpty()) {
        vm.submitPassphrase(passphrase)
        passphrase = ""
    }
}

@Composable
internal fun TotpScreen(vm: AccessViewModel) {
    var code by remember { mutableStateOf("") }
    Brand()
    Title(stringResource(R.string.login_title))
    Text(stringResource(R.string.login_step2), style = MaterialTheme.typography.bodyLarge)
    // L'écran n'affiche jamais le code attendu.
    CodeField(code, { code = it }, !vm.busy) {
        vm.submitTotp(code)
        code = ""
    }
    MessageLine(vm.message)
    Text(stringResource(R.string.login_time_left, formatDuration(vm.totpRemainingMs)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (vm.busy) BusyRow()
    WideButton(stringResource(R.string.login_open), enabled = !vm.busy && code.length == 6) {
        vm.submitTotp(code)
        code = ""
    }
    WideOutlinedButton(stringResource(R.string.login_cancel), enabled = !vm.busy) { vm.cancelTotp() }
}

@Composable
internal fun UnreadableScreen() {
    Brand()
    InfoCard(stringResource(R.string.vault_unreadable_title), stringResource(R.string.vault_unreadable), warning = true)
}
