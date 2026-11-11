package app.mespapiers.core

import app.mespapiers.core.access.BackoffState
import app.mespapiers.core.access.DeviceKey
import app.mespapiers.core.access.DeviceKeyUnavailableException
import app.mespapiers.core.access.GateRecord
import app.mespapiers.core.access.GateStore
import app.mespapiers.core.crypto.Aead
import app.mespapiers.core.crypto.AeadException
import app.mespapiers.core.crypto.KdfAlgo
import app.mespapiers.core.crypto.KdfParams
import app.mespapiers.core.crypto.PassphraseKdf
import app.mespapiers.core.util.Clock

class FakeClock(var wall: Long = 1_700_000_000_000, var elapsed: Long = 50_000, var boots: Int = 7) : Clock {
    override fun wallMillis() = wall
    override fun elapsedMillis() = elapsed
    override fun bootCount() = boots

    /** Le temps passe : l'horloge murale et le temps monotone avancent ensemble. */
    fun advance(ms: Long) {
        wall += ms
        elapsed += ms
    }

    /** Redémarrage du téléphone : le temps monotone repart de zéro. */
    fun reboot() {
        boots++
        elapsed = 1_000
    }
}

/** Stockage en mémoire ; « redémarrer » revient à créer un nouveau SessionGate dessus. */
class MemStore : GateStore {
    var record: GateRecord? = null
    var backoff = BackoffState()
    var lastStep = Long.MIN_VALUE
    var saves = 0

    override fun load() = record
    override fun save(record: GateRecord) {
        this.record = record
        saves++
    }

    override fun loadBackoff() = backoff
    override fun saveBackoff(state: BackoffState) {
        backoff = state
    }

    override fun lastTotpStep() = lastStep

    @Synchronized
    override fun advanceTotpStep(step: Long): Boolean {
        if (step <= lastStep) return false
        lastStep = step
        return true
    }
}

/** Remplace Android Keystore dans les tests JVM : clé logicielle, avec compteur d'usages. */
class SoftDeviceKey : DeviceKey {
    private val key = Aead.newKey()
    private val ctx = "device".toByteArray()
    var lost = false
    var unwraps = 0

    override fun wrap(plain: ByteArray) = Aead.seal(key, plain, ctx)

    override fun unwrap(blob: ByteArray): ByteArray {
        if (lost) throw DeviceKeyUnavailableException()
        unwraps++
        try {
            return Aead.open(key, blob, ctx)
        } catch (e: AeadException) {
            throw DeviceKeyUnavailableException(e)
        }
    }
}

/** Paramètres volontairement faibles pour que les tests restent rapides. */
fun fastKdf() = KdfParams(KdfAlgo.ARGON2ID, KdfParams.CURRENT_VERSION, PassphraseKdf.newSalt(), 64, 1, 1)

fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
