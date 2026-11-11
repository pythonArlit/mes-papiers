package app.mespapiers.core

import app.mespapiers.core.access.BackoffState
import app.mespapiers.core.access.FileGateStore
import app.mespapiers.core.access.GateRecord
import app.mespapiers.core.access.PassphraseResult
import app.mespapiers.core.access.SessionGate
import app.mespapiers.core.access.Totp
import app.mespapiers.core.access.TotpRejection
import app.mespapiers.core.access.TotpResult
import app.mespapiers.core.util.Base32
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class StoreTest {
    private val dir: File = Files.createTempDirectory("mp-gate").toFile()

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun installation_neuve_rien_n_est_configure() {
        val s = FileGateStore(dir)
        assertNull(s.load())
        assertEquals(BackoffState(), s.loadBackoff())
        assertEquals(Long.MIN_VALUE, s.lastTotpStep())
    }

    @Test
    fun l_etat_survit_a_la_relance() {
        val kdf = fastKdf()
        FileGateStore(dir).apply {
            save(GateRecord(kdf, ByteArray(60) { 1 }, ByteArray(61) { 2 }))
            saveBackoff(BackoffState(6, 60_000, 123_456, 9))
            assertTrue(advanceTotpStep(56_666_666))
        }
        val again = FileGateStore(dir)
        val r = again.load()!!
        assertArrayEquals(kdf.salt, r.kdf.salt)
        assertArrayEquals(ByteArray(60) { 1 }, r.seedBlob)
        assertArrayEquals(ByteArray(61) { 2 }, r.dataKeyBlob)
        assertEquals(BackoffState(6, 60_000, 123_456, 9), again.loadBackoff())
        assertEquals(56_666_666L, again.lastTotpStep())
    }

    @Test
    fun dernier_pas_totp_ne_recule_jamais() {
        val s = FileGateStore(dir)
        assertTrue(s.advanceTotpStep(100))
        assertFalse(s.advanceTotpStep(100))
        assertFalse(s.advanceTotpStep(99))
        assertEquals(100L, FileGateStore(dir).lastTotpStep())
        assertTrue(s.advanceTotpStep(101))
    }

    @Test
    fun ecriture_interrompue_l_ancien_etat_reste_lisible() {
        val s = FileGateStore(dir)
        s.saveBackoff(BackoffState(5, 30_000, 1_000, 3))
        // Arrêt brutal pendant l'écriture suivante : il reste un fichier temporaire partiel.
        File(dir, "backoff.bin.tmp").writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(BackoffState(5, 30_000, 1_000, 3), FileGateStore(dir).loadBackoff())
        s.saveBackoff(BackoffState(6, 60_000, 2_000, 3))
        assertEquals(6, FileGateStore(dir).loadBackoff().failures)
    }

    @Test
    fun fichier_abime_n_est_jamais_pris_pour_une_installation_neuve() {
        val s = FileGateStore(dir)
        s.save(GateRecord(fastKdf(), ByteArray(60), ByteArray(60)))
        val gate = File(dir, "gate.bin")
        for (damaged in listOf(gate.readBytes().copyOf(9), ByteArray(0), "n'importe quoi".toByteArray())) {
            gate.writeBytes(damaged)
            try {
                FileGateStore(dir).load()
                fail("un état abîmé a été lu comme valide ou absent")
            } catch (e: IOException) {
            }
        }
    }

    @Test
    fun aucun_secret_en_clair_dans_les_fichiers_d_etat() {
        val clock = FakeClock()
        val store = FileGateStore(dir)
        val gate = SessionGate(store, SoftDeviceKey(), SoftDeviceKey(), clock, newKdfParams = { fastKdf() })
        val pass = "ma phrase très secrète"
        val e = gate.beginSetup(pass.toCharArray())
        val seed = Base32.decode(e.manualKey())
        assertNull(gate.confirmSetup(e, Totp.code(seed, Totp.stepAt(clock.wallMillis() / 1000))))
        val dataKey = gate.withDataKey { it.copyOf() }
        gate.lock()

        val all = dir.listFiles()!!.sortedBy { it.name }
        assertEquals(listOf("backoff.bin", "gate.bin", "totp-step.bin"), all.map { it.name })
        val dump = all.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertFalse(dump.contains(pass))
        assertFalse(dump.contains(String(seed, Charsets.ISO_8859_1)))
        assertFalse(dump.contains(String(dataKey, Charsets.ISO_8859_1)))
        assertFalse(dump.contains(Base32.encode(seed)))
    }

    @Test
    fun double_validation_de_bout_en_bout_sur_disque_avec_relance() {
        val clock = FakeClock()
        val seedKey = SoftDeviceKey()
        val dataKey = SoftDeviceKey()
        fun gate() = SessionGate(FileGateStore(dir), seedKey, dataKey, clock, newKdfParams = { fastKdf() })
        val pass = "ma phrase très secrète"

        var g = gate()
        val e = g.beginSetup(pass.toCharArray())
        val seed = Base32.decode(e.manualKey())
        fun code() = Totp.code(seed, Totp.stepAt(clock.wallMillis() / 1000))
        assertNull(g.confirmSetup(e, code()))
        val key = g.withDataKey { it.toHex() }

        // Relance de l'application : tout repart du disque.
        clock.advance(60_000)
        g = gate()
        assertTrue(g.isConfigured)
        assertFalse(g.isOpen)
        repeat(5) { g.submitPassphrase("mauvaise phrase 123".toCharArray()) }
        g = gate()
        assertEquals(PassphraseResult.Throttled(30_000), g.submitPassphrase(pass.toCharArray()))
        clock.advance(30_000)
        assertEquals(PassphraseResult.Accepted, g.submitPassphrase(pass.toCharArray()))
        val used = code()
        assertEquals(TotpResult.Opened, g.submitTotp(used))
        assertEquals(key, g.withDataKey { it.toHex() })

        g = gate()
        assertEquals(PassphraseResult.Accepted, g.submitPassphrase(pass.toCharArray()))
        assertEquals(TotpResult.Rejected(TotpRejection.ALREADY_USED, 4), g.submitTotp(used))
    }
}
