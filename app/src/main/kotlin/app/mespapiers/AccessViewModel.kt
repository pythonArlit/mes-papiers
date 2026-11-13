package app.mespapiers

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import app.mespapiers.core.archive.ArchiveError
import app.mespapiers.core.archive.ArchiveException
import app.mespapiers.core.crypto.Aead
import app.mespapiers.core.docs.RecoveryPhrase
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mespapiers.core.access.PassphrasePolicy
import app.mespapiers.core.access.PassphraseResult
import app.mespapiers.core.access.PendingEnrollment
import app.mespapiers.core.access.TotpRejection
import app.mespapiers.core.access.TotpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface Screen {
    data object Loading : Screen
    data object Intro : Screen
    data object CreatePassphrase : Screen

    /** Restauration d'une archive dans une installation vide. */
    data object Restore : Screen

    /** La graine n'existe ici qu'en mémoire, le temps de l'enrôlement. */
    data class Enroll(val otpAuthUri: String, val manualKey: String) : Screen
    data object Passphrase : Screen
    data object Totp : Screen
    data object Home : Screen
    data object Unreadable : Screen
}

/** Message affiché à l'utilisateur, résolu depuis les ressources. */
data class UiMessage(@StringRes val res: Int, val arg: Any? = null)

/**
 * État de l'accès. Rien de sensible n'est placé dans un état sauvegardé d'activité :
 * après la mort du processus, on repart de l'écran de connexion.
 */
class AccessViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as MesPapiersApp).container

    var screen by mutableStateOf<Screen>(Screen.Loading)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<UiMessage?>(null)
        private set

    /** Délai progressif restant avant de pouvoir saisir la phrase. */
    var waitMs by mutableStateOf(0L)
        private set

    /** Temps restant pour saisir le code TOTP. */
    var totpRemainingMs by mutableStateOf(0L)
        private set

    val deviceSecure: Boolean get() = c.isDeviceSecure

    private var enrollment: PendingEnrollment? = null

    init {
        refresh()
        viewModelScope.launch {
            while (true) {
                delay(1000)
                tick()
            }
        }
    }

    // ------------------------------------------------------------ cycle de vie

    fun onInteraction() = c.timers.onInteraction(c.clock.elapsedMillis())

    fun onBackground() = c.timers.onBackground(c.clock.elapsedMillis())

    fun onForeground() {
        if (busy) return
        val now = c.clock.elapsedMillis()
        if (c.gate.isOpen && c.timers.shouldLock(now)) c.lockNow() else c.timers.onForeground(now)
        refresh()
    }

    private fun tick() {
        // Pendant une dérivation, le portier est occupé : on ne l'interroge pas depuis le fil principal.
        if (busy) return
        try {
            if (c.gate.isOpen && c.timers.shouldLock(c.clock.elapsedMillis())) c.lockNow()
            when (screen) {
                Screen.Home -> if (!c.gate.isOpen) {
                    c.vaultState.clear()
                    screen = Screen.Passphrase
                }
                Screen.Totp -> {
                    totpRemainingMs = c.gate.totpRemainingMs()
                    if (!c.gate.isAwaitingTotp) {
                        message = UiMessage(R.string.totp_back_to_passphrase)
                        screen = Screen.Passphrase
                    }
                }
                Screen.Passphrase -> waitMs = c.gate.throttleRemainingMs()
                else -> Unit
            }
        } catch (e: IOException) {
            screen = Screen.Unreadable
        }
    }

    private fun refresh() {
        screen = try {
            when {
                enrollment != null -> screen
                // Un retour du sélecteur de fichiers ne doit pas faire perdre l'écran en cours.
                !c.gate.isConfigured -> if (screen == Screen.CreatePassphrase || screen == Screen.Restore) screen else Screen.Intro
                c.gate.isOpen -> Screen.Home
                c.gate.isAwaitingTotp -> Screen.Totp
                else -> Screen.Passphrase
            }
        } catch (e: IOException) {
            Screen.Unreadable
        }
        if (screen == Screen.Passphrase) waitMs = try {
            c.gate.throttleRemainingMs()
        } catch (e: IOException) {
            0
        }
    }

    // ------------------------------------------------------------ configuration

    /** Nombre de documents d'une archive restaurée, en attente de la configuration du coffre. */
    var restoredCount by mutableStateOf<Int?>(null)
        private set

    private fun discardRestore() {
        restoredCount = null
        if (c.pendingRestoreKey != null) viewModelScope.launch(Dispatchers.IO) { c.discardRestore() }
    }

    fun startSetup() {
        message = null
        discardRestore()
        screen = Screen.CreatePassphrase
    }

    fun startRestore() {
        message = null
        discardRestore()
        screen = Screen.Restore
    }

    fun backToIntro() {
        if (busy) return
        message = null
        discardRestore()
        screen = Screen.Intro
    }

    /**
     * Lit et vérifie l'archive, et prépare son contenu sous une clé neuve gardée en mémoire.
     * Rien n'est accessible tant que la phrase d'accès et le code n'ont pas été configurés ;
     * en cas d'erreur l'installation reste vide.
     */
    fun restoreFrom(uri: Uri, phrase: String) {
        if (busy) return
        val normalized = RecoveryPhrase.normalize(phrase.toCharArray())
        if (normalized == null) {
            message = UiMessage(R.string.restore_phrase_format)
            return
        }
        busy = true
        message = null
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val resolver = c.appContext.contentResolver
                    var size = -1L
                    try {
                        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) size = it.getLong(0) }
                    } catch (e: Exception) {
                        // Taille inconnue : l'espace sera vérifié à l'écriture.
                    }
                    if (size > 0 && c.attachments.usableSpace < size + 8L * 1024 * 1024) throw ArchiveException(ArchiveError.STORAGE)
                    val input = resolver.openInputStream(uri) ?: throw ArchiveException(ArchiveError.BAD_FORMAT)
                    val key = Aead.newKey()
                    val summary = c.backup.restore(input.buffered(), normalized, key)
                    c.pendingRestoreKey = key
                    summary.records
                }
                restoredCount = count
                screen = Screen.CreatePassphrase
            } catch (e: ArchiveException) {
                message = UiMessage(
                    when (e.error) {
                        ArchiveError.BAD_FORMAT -> R.string.restore_bad_format
                        ArchiveError.UNKNOWN_VERSION -> R.string.restore_unknown_version
                        ArchiveError.WRONG_SECRET -> R.string.restore_wrong_phrase
                        ArchiveError.CORRUPTED -> R.string.restore_corrupted
                        ArchiveError.LIMIT_EXCEEDED -> R.string.restore_too_big
                        ArchiveError.STORAGE -> R.string.restore_storage
                    },
                )
            } catch (e: Exception) {
                message = UiMessage(R.string.restore_failed)
            } finally {
                normalized.fill('\u0000')
                busy = false
            }
        }
    }

    /** La restauration préparée est activée : sa clé n'a plus à rester en mémoire. */
    private fun restoreActivated() {
        c.pendingRestoreKey?.fill(0)
        c.pendingRestoreKey = null
        restoredCount = null
    }

    fun createPassphrase(passphrase: String, confirmation: String) {
        if (busy) return
        val chars = passphrase.toCharArray()
        when {
            !PassphrasePolicy.isLongEnough(chars) -> message = UiMessage(R.string.create_too_short)
            passphrase != confirmation -> message = UiMessage(R.string.create_mismatch)
            else -> gateCall(onError = Screen.Intro) {
                c.calibrateKdf()
                c.createDeviceKeys()
                val pending = c.gate.beginSetup(chars, c.pendingRestoreKey)
                chars.fill('\u0000')
                if (SecondFactor.AUTOMATIC) {
                    // Démo : la seconde étape est faite automatiquement (voir SecondFactor).
                    SecondFactor.remember(c.appContext, pending.manualKey())
                    val confirmed = SecondFactor.codes(c.appContext).any { c.gate.confirmSetup(pending, it) == null }
                    message = null
                    if (confirmed) {
                        restoreActivated()
                        c.timers.onUnlocked(c.clock.elapsedMillis())
                        screen = Screen.Home
                    } else {
                        pending.discard()
                        screen = Screen.Intro
                    }
                } else {
                    enrollment = pending
                    message = null
                    screen = Screen.Enroll(pending.otpAuthUri(), pending.manualKey())
                }
            }
        }
    }

    fun confirmEnrollment(code: String) {
        val pending = enrollment ?: return
        if (busy) return
        gateCall(onError = Screen.Intro) {
            when (c.gate.confirmSetup(pending, code)) {
                null -> {
                    enrollment = null
                    restoreActivated()
                    c.timers.onUnlocked(c.clock.elapsedMillis())
                    message = null
                    screen = Screen.Home
                }
                TotpRejection.EXPIRED -> message = UiMessage(R.string.totp_enroll_expired)
                else -> message = UiMessage(R.string.totp_enroll_wrong)
            }
        }
    }

    fun cancelEnrollment() {
        enrollment?.discard()
        enrollment = null
        message = null
        discardRestore()
        screen = Screen.Intro
    }

    fun onNoAuthenticatorApp() {
        message = UiMessage(R.string.enroll_no_app)
    }

    // ------------------------------------------------------------ connexion

    fun submitPassphrase(passphrase: String) {
        if (busy) return
        val chars = passphrase.toCharArray()
        gateCall(onError = Screen.Unreadable) {
            val result = c.gate.submitPassphrase(chars)
            chars.fill('\u0000')
            when (result) {
                PassphraseResult.Accepted -> if (SecondFactor.AUTOMATIC) {
                    val opened = SecondFactor.codes(c.appContext).any { c.gate.submitTotp(it) == TotpResult.Opened }
                    if (opened) {
                        c.timers.onUnlocked(c.clock.elapsedMillis())
                        message = null
                        screen = Screen.Home
                    } else {
                        c.gate.cancelPending()
                        message = UiMessage(R.string.demo_retry)
                    }
                } else {
                    message = null
                    totpRemainingMs = c.gate.totpRemainingMs()
                    screen = Screen.Totp
                }
                is PassphraseResult.Wrong -> {
                    waitMs = result.waitMs
                    message = UiMessage(R.string.passphrase_wrong)
                }
                is PassphraseResult.Throttled -> {
                    waitMs = result.waitMs
                    message = null
                }
                PassphraseResult.NotConfigured -> screen = Screen.Intro
                PassphraseResult.VaultUnreadable -> screen = Screen.Unreadable
            }
        }
    }

    fun submitTotp(code: String) {
        if (busy) return
        gateCall(onError = Screen.Unreadable) {
            when (val result = c.gate.submitTotp(code)) {
                TotpResult.Opened -> {
                    c.timers.onUnlocked(c.clock.elapsedMillis())
                    message = null
                    screen = Screen.Home
                }
                is TotpResult.Rejected -> message = when (result.reason) {
                    TotpRejection.WRONG -> UiMessage(R.string.totp_wrong, result.attemptsLeft)
                    TotpRejection.EXPIRED -> UiMessage(R.string.totp_expired, result.attemptsLeft)
                    TotpRejection.ALREADY_USED -> UiMessage(R.string.totp_already_used, result.attemptsLeft)
                }
                is TotpResult.BackToPassphrase -> {
                    waitMs = result.waitMs
                    message = UiMessage(R.string.totp_back_to_passphrase)
                    screen = Screen.Passphrase
                }
                TotpResult.NoPendingStep -> screen = Screen.Passphrase
                TotpResult.VaultUnreadable -> screen = Screen.Unreadable
            }
        }
    }

    fun cancelTotp() {
        if (busy) return
        c.gate.cancelPending()
        message = null
        refresh()
    }

    fun lock() {
        c.lockNow()
        message = null
        refresh()
    }

    /**
     * Exécute une opération du portier hors du fil principal (la dérivation dure jusqu'à
     * une seconde), puis revient sur le fil principal. Les états Compose modifiés dans
     * [block] le sont depuis un fil d'arrière-plan : c'est permis par le système d'instantanés.
     */
    private fun gateCall(onError: Screen, block: () -> Unit) {
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) { block() }
            } catch (e: IOException) {
                screen = onError
            } catch (e: java.security.GeneralSecurityException) {
                screen = onError
            } finally {
                busy = false
            }
        }
    }
}
