package app.mespapiers

import android.app.Application
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
        if (c.gate.isOpen && c.timers.shouldLock(now)) c.gate.lock() else c.timers.onForeground(now)
        refresh()
    }

    private fun tick() {
        // Pendant une dérivation, le portier est occupé : on ne l'interroge pas depuis le fil principal.
        if (busy) return
        try {
            if (c.gate.isOpen && c.timers.shouldLock(c.clock.elapsedMillis())) c.gate.lock()
            when (screen) {
                Screen.Home -> if (!c.gate.isOpen) screen = Screen.Passphrase
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
                !c.gate.isConfigured -> Screen.Intro
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

    fun startSetup() {
        message = null
        screen = Screen.CreatePassphrase
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
                val pending = c.gate.beginSetup(chars)
                chars.fill('\u0000')
                enrollment = pending
                message = null
                screen = Screen.Enroll(pending.otpAuthUri(), pending.manualKey())
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
                PassphraseResult.Accepted -> {
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
        c.gate.lock()
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
