package app.mespapiers.core.access

import app.mespapiers.core.crypto.Aead
import app.mespapiers.core.crypto.AeadException
import app.mespapiers.core.crypto.CryptoContext
import app.mespapiers.core.crypto.KdfParams
import app.mespapiers.core.crypto.PassphraseKdf
import app.mespapiers.core.util.Clock
import app.mespapiers.core.util.Hkdf
import app.mespapiers.core.util.wipe
import java.security.GeneralSecurityException

/**
 * Clé liée à l'appareil et non exportable (Android Keystore dans l'application).
 * [unwrap] lève [DeviceKeyUnavailableException] si la clé a été invalidée ou perdue.
 */
interface DeviceKey {
    fun wrap(plain: ByteArray): ByteArray
    fun unwrap(blob: ByteArray): ByteArray
}

class DeviceKeyUnavailableException(cause: Throwable? = null) :
    GeneralSecurityException("Clé de l'appareil indisponible", cause)

class VaultLockedException : IllegalStateException("Coffre verrouillé")

/** Ce qui est conservé hors session pour pouvoir ouvrir le coffre. Rien n'y est en clair. */
class GateRecord(val kdf: KdfParams, val seedBlob: ByteArray, val dataKeyBlob: ByteArray)

/**
 * Stockage de l'état d'accès. Chaque écriture doit être durable avant de rendre la main.
 */
interface GateStore {
    fun load(): GateRecord?
    fun save(record: GateRecord)
    fun loadBackoff(): BackoffState
    fun saveBackoff(state: BackoffState)

    /** Dernier pas TOTP accepté, ou [Long.MIN_VALUE]. */
    fun lastTotpStep(): Long

    /**
     * Enregistre [step] comme dernier pas accepté si et seulement s'il est strictement
     * supérieur au pas enregistré. Opération atomique et durable ; renvoie false sinon.
     */
    fun advanceTotpStep(step: Long): Boolean
}

object PassphrasePolicy {
    const val MIN_LENGTH = 12

    fun isLongEnough(passphrase: CharArray): Boolean =
        Character.codePointCount(passphrase, 0, passphrase.size) >= MIN_LENGTH
}

sealed interface PassphraseResult {
    /** Étape 1 réussie : le TOTP est attendu. Aucune fiche n'est encore accessible. */
    data object Accepted : PassphraseResult
    data class Wrong(val waitMs: Long) : PassphraseResult
    data class Throttled(val waitMs: Long) : PassphraseResult
    data object NotConfigured : PassphraseResult

    /** Clé Keystore invalidée ou perdue : seule une restauration d'archive reste possible. */
    data object VaultUnreadable : PassphraseResult
}

enum class TotpRejection { WRONG, EXPIRED, ALREADY_USED }

sealed interface TotpResult {
    data object Opened : TotpResult
    data class Rejected(val reason: TotpRejection, val attemptsLeft: Int) : TotpResult

    /** Trop de tentatives ou délai dépassé : retour à l'étape 1, l'échec est compté. */
    data class BackToPassphrase(val waitMs: Long) : TotpResult
    data object NoPendingStep : TotpResult
    data object VaultUnreadable : TotpResult
}

enum class ChangeResult { DONE, WRONG_CURRENT, THROTTLED, NOT_OPEN, TOO_SHORT, VAULT_UNREADABLE }

/** Enrôlement en cours : rien n'est persisté tant que le premier code n'est pas vérifié. */
class PendingEnrollment internal constructor(
    internal val seed: ByteArray,
    internal val dataKey: ByteArray?,
    internal val record: GateRecord,
) {
    /** Lien otpauth:// à afficher en QR ou à ouvrir dans l'application d'authentification. */
    fun otpAuthUri(): String = Totp.otpAuthUri(seed)
    fun manualKey(): String = Totp.manualKey(seed)

    fun discard() {
        seed.wipe()
        dataKey?.wipe()
    }
}

/**
 * Double validation et hiérarchie de clés.
 *
 * phrase --Argon2id--> maître --HKDF--> kSeed, kData
 *   graine TOTP   = Keystore_graine( AEAD(kSeed, graine) )
 *   clé de données = Keystore_données( AEAD(kData, DK) )
 *
 * Étape 1 : la phrase est vérifiée en déchiffrant la graine, ce qui exige aussi la clé
 * Keystore : les fichiers extraits du téléphone ne permettent pas de tester une phrase.
 * Étape 2 : la clé de données n'est désenveloppée qu'après un TOTP valide. Entre les deux,
 * seuls la graine et kData restent en mémoire, jusqu'à [totpWindowMs] au plus.
 *
 * Le TOTP ne dérive aucune clé : c'est une barrière logique (voir MENACES.md).
 */
class SessionGate(
    private val store: GateStore,
    private val seedDeviceKey: DeviceKey,
    private val dataDeviceKey: DeviceKey,
    private val clock: Clock,
    private val backoff: Backoff = Backoff(),
    private val verifier: TotpVerifier = TotpVerifier(),
    private val maxTotpAttempts: Int = 5,
    private val totpWindowMs: Long = 180_000,
    private val newKdfParams: () -> KdfParams = { PassphraseKdf.argon2id() },
) {
    private class Pending(val seed: ByteArray, val kData: ByteArray, val deadlineElapsed: Long, val bootCount: Int) {
        var attempts = 0
        fun wipe() {
            seed.wipe()
            kData.wipe()
        }
    }

    private class Keks(val kSeed: ByteArray, val kData: ByteArray) {
        fun wipe() {
            kSeed.wipe()
            kData.wipe()
        }
    }

    private val mutex = Any()
    private var pending: Pending? = null
    private var dataKey: ByteArray? = null

    private companion object {
        val CTX_SEED = CryptoContext.of("mp", "totp-seed", "v1")
        val CTX_DK = CryptoContext.of("mp", "data-key", "v1")
    }

    val isConfigured: Boolean get() = store.load() != null
    val isOpen: Boolean get() = synchronized(mutex) { dataKey != null }
    val isAwaitingTotp: Boolean get() = synchronized(mutex) { expirePendingIfNeeded(); pending != null }

    /** Temps restant pour saisir le TOTP, 0 si aucune étape intermédiaire n'est en cours. */
    fun totpRemainingMs(): Long = synchronized(mutex) {
        expirePendingIfNeeded()
        val p = pending ?: return 0
        (p.deadlineElapsed - clock.elapsedMillis()).coerceAtLeast(0)
    }

    /** Délai progressif restant avant de pouvoir retenter l'étape 1. */
    fun throttleRemainingMs(): Long = synchronized(mutex) { backoff.remainingMs(currentBackoff(), clock) }

    // ---------------------------------------------------------------- configuration

    /**
     * @param dataKey clé de données déjà choisie (restauration d'une archive : le contenu a été
     *   préparé sous cette clé avant que le coffre n'existe). Sinon une clé neuve est tirée.
     */
    fun beginSetup(passphrase: CharArray, dataKey: ByteArray? = null): PendingEnrollment = synchronized(mutex) {
        check(store.load() == null) { "Coffre déjà configuré" }
        require(PassphrasePolicy.isLongEnough(passphrase)) { "Phrase trop courte" }
        val params = newKdfParams()
        val keks = deriveKeks(passphrase, params)
        require(dataKey == null || dataKey.size == Aead.KEY_BYTES) { "Clé de données invalide" }
        val dk = dataKey?.copyOf() ?: Aead.newKey()
        val seed = Totp.newSeed()
        try {
            val record = GateRecord(
                params,
                seedDeviceKey.wrap(Aead.seal(keks.kSeed, seed, CTX_SEED)),
                dataDeviceKey.wrap(Aead.seal(keks.kData, dk, CTX_DK)),
            )
            PendingEnrollment(seed, dk, record)
        } finally {
            keks.wipe()
        }
    }

    /**
     * Vérifie le premier code, puis seulement persiste le coffre et ouvre la session.
     * Après cet appel réussi, la graine n'est plus jamais affichable.
     */
    fun confirmSetup(enrollment: PendingEnrollment, code: String): TotpRejection? = synchronized(mutex) {
        check(store.load() == null) { "Coffre déjà configuré" }
        val dk = checkNotNull(enrollment.dataKey)
        val step = checkEnrollmentCode(enrollment, code) ?: return rejectionFor(enrollment, code)
        store.save(enrollment.record)
        store.advanceTotpStep(step)
        store.saveBackoff(backoff.onSuccess())
        dataKey = dk.copyOf()
        enrollment.discard()
        null
    }

    // ---------------------------------------------------------------- étape 1

    fun submitPassphrase(passphrase: CharArray): PassphraseResult = synchronized(mutex) {
        val record = store.load() ?: return PassphraseResult.NotConfigured
        expirePendingIfNeeded()
        dropPending()
        val wait = backoff.remainingMs(currentBackoff(), clock)
        if (wait > 0) return PassphraseResult.Throttled(wait)

        val keks = deriveKeks(passphrase, record.kdf)
        val seed = try {
            Aead.open(keks.kSeed, seedDeviceKey.unwrap(record.seedBlob), CTX_SEED)
        } catch (e: DeviceKeyUnavailableException) {
            keks.wipe()
            return PassphraseResult.VaultUnreadable
        } catch (e: AeadException) {
            keks.wipe()
            val state = backoff.onFailure(store.loadBackoff(), clock)
            store.saveBackoff(state)
            return PassphraseResult.Wrong(backoff.remainingMs(state, clock))
        }
        keks.kSeed.wipe()
        // L'étape intermédiaire est comptée comme un échec dès maintenant et de façon
        // durable ; seul un TOTP valide remet le compteur à zéro. Tuer l'application
        // pour repartir à zéro ne fait donc pas disparaître la tentative.
        store.saveBackoff(backoff.onFailure(store.loadBackoff(), clock))
        pending = Pending(seed, keks.kData, clock.elapsedMillis() + totpWindowMs, clock.bootCount())
        PassphraseResult.Accepted
    }

    // ---------------------------------------------------------------- étape 2

    fun submitTotp(code: String): TotpResult = synchronized(mutex) {
        if (expirePendingIfNeeded()) return TotpResult.BackToPassphrase(backoff.remainingMs(store.loadBackoff(), clock))
        val p = pending ?: return TotpResult.NoPendingStep

        val check = verifier.check(p.seed, code, clock.wallMillis() / 1000, store.lastTotpStep())
        val rejection = when (check) {
            is TotpCheck.Valid -> if (store.advanceTotpStep(check.step)) null else TotpRejection.ALREADY_USED
            TotpCheck.AlreadyUsed -> TotpRejection.ALREADY_USED
            TotpCheck.Expired -> TotpRejection.EXPIRED
            TotpCheck.Wrong -> TotpRejection.WRONG
        }
        if (rejection != null) {
            p.attempts++
            if (p.attempts >= maxTotpAttempts) {
                return TotpResult.BackToPassphrase(failPending())
            }
            return TotpResult.Rejected(rejection, maxTotpAttempts - p.attempts)
        }

        val record = store.load() ?: return TotpResult.NoPendingStep
        val dk = try {
            Aead.open(p.kData, dataDeviceKey.unwrap(record.dataKeyBlob), CTX_DK)
        } catch (e: GeneralSecurityException) {
            dropPending()
            return TotpResult.VaultUnreadable
        }
        dropPending()
        dataKey = dk
        store.saveBackoff(backoff.onSuccess())
        TotpResult.Opened
    }

    /** Abandon volontaire de l'étape 2 (retour arrière, extinction de l'écran). */
    fun cancelPending() = synchronized(mutex) {
        if (pending != null) failPending()
        Unit
    }

    // ---------------------------------------------------------------- session

    fun lock() = synchronized(mutex) {
        dataKey?.wipe()
        dataKey = null
        if (pending != null) failPending()
        Unit
    }

    /** Donne accès à la clé de données le temps de [block]. Ne pas la conserver. */
    fun <T> withDataKey(block: (ByteArray) -> T): T {
        val copy = synchronized(mutex) { dataKey?.copyOf() } ?: throw VaultLockedException()
        try {
            return block(copy)
        } finally {
            copy.wipe()
        }
    }

    // ---------------------------------------------------------------- paramètres

    /** Change la phrase : session ouverte, phrase actuelle ressaisie. */
    fun changePassphrase(current: CharArray, next: CharArray): ChangeResult = synchronized(mutex) {
        val dk = dataKey ?: return ChangeResult.NOT_OPEN
        if (!PassphrasePolicy.isLongEnough(next)) return ChangeResult.TOO_SHORT
        val record = store.load() ?: return ChangeResult.NOT_OPEN
        val seed = when (val v = verifyCurrent(current, record)) {
            is Verified.Ok -> v.seed
            is Verified.Failed -> return v.result
        }
        val params = newKdfParams()
        val keks = deriveKeks(next, params)
        try {
            store.save(
                GateRecord(
                    params,
                    seedDeviceKey.wrap(Aead.seal(keks.kSeed, seed, CTX_SEED)),
                    dataDeviceKey.wrap(Aead.seal(keks.kData, dk, CTX_DK)),
                ),
            )
        } finally {
            keks.wipe()
            seed.wipe()
        }
        ChangeResult.DONE
    }

    /** Prépare un nouvel enrôlement TOTP ; l'ancienne graine reste active jusqu'à confirmation. */
    fun beginTotpReenroll(current: CharArray): Pair<ChangeResult, PendingEnrollment?> = synchronized(mutex) {
        if (dataKey == null) return ChangeResult.NOT_OPEN to null
        val record = store.load() ?: return ChangeResult.NOT_OPEN to null
        when (val v = verifyCurrent(current, record)) {
            is Verified.Failed -> return v.result to null
            is Verified.Ok -> v.seed.wipe()
        }
        val keks = deriveKeks(current, record.kdf)
        val seed = Totp.newSeed()
        try {
            val next = GateRecord(record.kdf, seedDeviceKey.wrap(Aead.seal(keks.kSeed, seed, CTX_SEED)), record.dataKeyBlob)
            ChangeResult.DONE to PendingEnrollment(seed, null, next)
        } finally {
            keks.wipe()
        }
    }

    /** Remplace la graine après vérification d'un code de la nouvelle ; l'ancienne est écrasée. */
    fun confirmTotpReenroll(enrollment: PendingEnrollment, code: String): TotpRejection? = synchronized(mutex) {
        if (dataKey == null) throw VaultLockedException()
        val step = checkEnrollmentCode(enrollment, code) ?: return rejectionFor(enrollment, code)
        store.save(enrollment.record)
        store.advanceTotpStep(step)
        enrollment.discard()
        null
    }

    // ---------------------------------------------------------------- interne

    private sealed interface Verified {
        class Ok(val seed: ByteArray) : Verified
        class Failed(val result: ChangeResult) : Verified
    }

    private fun verifyCurrent(current: CharArray, record: GateRecord): Verified {
        if (backoff.remainingMs(currentBackoff(), clock) > 0) return Verified.Failed(ChangeResult.THROTTLED)
        val keks = deriveKeks(current, record.kdf)
        try {
            return Verified.Ok(Aead.open(keks.kSeed, seedDeviceKey.unwrap(record.seedBlob), CTX_SEED))
        } catch (e: DeviceKeyUnavailableException) {
            return Verified.Failed(ChangeResult.VAULT_UNREADABLE)
        } catch (e: AeadException) {
            store.saveBackoff(backoff.onFailure(store.loadBackoff(), clock))
            return Verified.Failed(ChangeResult.WRONG_CURRENT)
        } finally {
            keks.wipe()
        }
    }

    /** Pour une graine neuve il n'y a pas de rejeu possible : seul le pas courant ± tolérance compte. */
    private fun checkEnrollmentCode(enrollment: PendingEnrollment, code: String): Long? =
        (verifier.check(enrollment.seed, code, clock.wallMillis() / 1000, Long.MIN_VALUE) as? TotpCheck.Valid)?.step

    private fun rejectionFor(enrollment: PendingEnrollment, code: String): TotpRejection =
        when (verifier.check(enrollment.seed, code, clock.wallMillis() / 1000, Long.MIN_VALUE)) {
            TotpCheck.Expired -> TotpRejection.EXPIRED
            else -> TotpRejection.WRONG
        }

    private fun deriveKeks(passphrase: CharArray, params: KdfParams): Keks {
        val master = PassphraseKdf.derive(passphrase, params)
        try {
            return Keks(
                Hkdf.derive(master, null, "mp/kek/seed/v1"),
                Hkdf.derive(master, null, "mp/kek/data/v1"),
            )
        } finally {
            master.wipe()
        }
    }

    private fun currentBackoff(): BackoffState {
        val stored = store.loadBackoff()
        val normalized = backoff.normalize(stored, clock)
        if (normalized != stored) store.saveBackoff(normalized)
        return normalized
    }

    private fun dropPending() {
        pending?.wipe()
        pending = null
    }

    /** L'échec a déjà été compté à l'étape 1 : on relance seulement le délai à partir de maintenant. */
    private fun failPending(): Long {
        dropPending()
        val stored = store.loadBackoff()
        val delay = backoff.delayFor(stored.failures)
        val state = stored.copy(delayMs = delay, lockedUntilElapsed = clock.elapsedMillis() + delay, bootCount = clock.bootCount())
        store.saveBackoff(state)
        return backoff.remainingMs(state, clock)
    }

    private fun expirePendingIfNeeded(): Boolean {
        val p = pending ?: return false
        if (p.bootCount != clock.bootCount() || clock.elapsedMillis() >= p.deadlineElapsed) {
            failPending()
            return true
        }
        return false
    }
}
