package app.mespapiers.core.access

import app.mespapiers.core.util.Base32
import app.mespapiers.core.util.constantTimeEquals
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** TOTP selon RFC 6238 : HMAC-SHA1, pas de 30 secondes, six chiffres. */
object Totp {
    const val PERIOD_SECONDS = 30L
    const val DIGITS = 6
    const val SEED_BYTES = 20 // 160 bits
    const val ISSUER = "Mes Papiers"

    fun newSeed(): ByteArray = ByteArray(SEED_BYTES).also { SecureRandom().nextBytes(it) }

    fun stepAt(epochSeconds: Long): Long = Math.floorDiv(epochSeconds, PERIOD_SECONDS)

    fun code(seed: ByteArray, step: Long, digits: Int = DIGITS): String {
        val msg = ByteArray(8)
        var v = step
        for (i in 7 downTo 0) {
            msg[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(seed, "HmacSHA1"))
        val h = mac.doFinal(msg)
        val offset = h[h.size - 1].toInt() and 0x0F
        val binary = ((h[offset].toInt() and 0x7F) shl 24) or
            ((h[offset + 1].toInt() and 0xFF) shl 16) or
            ((h[offset + 2].toInt() and 0xFF) shl 8) or
            (h[offset + 3].toInt() and 0xFF)
        var mod = 1
        repeat(digits) { mod *= 10 }
        return (binary % mod).toString().padStart(digits, '0')
    }

    /** Lien d'enrôlement. Il contient la graine : ne jamais le journaliser ni le conserver. */
    fun otpAuthUri(seed: ByteArray, account: String = "Coffre"): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return "otpauth://totp/${enc(ISSUER)}:${enc(account)}" +
            "?secret=${Base32.encode(seed)}&issuer=${enc(ISSUER)}&algorithm=SHA1&digits=$DIGITS&period=$PERIOD_SECONDS"
    }

    /** Clé pour la saisie manuelle, en groupes de quatre caractères. */
    fun manualKey(seed: ByteArray): String = Base32.encode(seed).chunked(4).joinToString(" ")
}

sealed interface TotpCheck {
    /** Code valide pour un pas strictement postérieur au dernier pas accepté. */
    data class Valid(val step: Long) : TotpCheck

    /** Code correct mais pour un pas déjà accepté (rejeu). */
    data object AlreadyUsed : TotpCheck

    /** Code correct pour un pas hors tolérance : code périmé ou horloge décalée. */
    data object Expired : TotpCheck

    data object Wrong : TotpCheck
}

/**
 * Vérification pure, sans effet de bord. L'anti-rejeu durable (avancer le dernier pas
 * accepté de façon atomique) est fait par l'appelant ; voir SessionGate.
 *
 * @param tolerance nombre de pas acceptés avant et après le pas courant.
 * @param diagnosticWindow pas examinés au-delà de la tolérance, uniquement pour distinguer
 *   « code expiré / horloge décalée » de « code erroné » dans le message affiché.
 */
class TotpVerifier(private val tolerance: Int = 1, private val diagnosticWindow: Int = 20) {

    fun check(seed: ByteArray, input: String, nowEpochSeconds: Long, lastAcceptedStep: Long): TotpCheck {
        val code = input.filterNot { it.isWhitespace() }
        if (code.length != Totp.DIGITS || !code.all { it in '0'..'9' }) return TotpCheck.Wrong
        val candidate = code.toByteArray(Charsets.US_ASCII)
        val current = Totp.stepAt(nowEpochSeconds)

        var validStep = Long.MIN_VALUE
        var used = false
        var outside = false
        // Tous les pas sont évalués, sans sortie anticipée, pour ne pas faire dépendre
        // la durée de la position du pas correspondant.
        for (step in (current - diagnosticWindow)..(current + diagnosticWindow)) {
            val expected = Totp.code(seed, step).toByteArray(Charsets.US_ASCII)
            val match = constantTimeEquals(expected, candidate)
            val inTolerance = step >= current - tolerance && step <= current + tolerance
            if (match && inTolerance) {
                if (step > lastAcceptedStep) {
                    if (step > validStep) validStep = step
                } else {
                    used = true
                }
            } else if (match) {
                outside = true
            }
        }
        return when {
            validStep != Long.MIN_VALUE -> TotpCheck.Valid(validStep)
            used -> TotpCheck.AlreadyUsed
            outside -> TotpCheck.Expired
            else -> TotpCheck.Wrong
        }
    }
}
