package app.mespapiers

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mespapiers.core.access.DeviceKeyUnavailableException
import app.mespapiers.core.access.FileGateStore
import app.mespapiers.core.access.PassphraseResult
import app.mespapiers.core.access.SessionGate
import app.mespapiers.core.access.Totp
import app.mespapiers.core.access.TotpRejection
import app.mespapiers.core.access.TotpResult
import app.mespapiers.core.access.VaultLockedException
import app.mespapiers.core.crypto.PassphraseKdf
import app.mespapiers.core.util.Base32
import app.mespapiers.platform.AndroidClock
import app.mespapiers.platform.KeystoreDeviceKey
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Tests instrumentés : vrai Android Keystore, vraie horloge, vrai stockage privé. */
@RunWith(AndroidJUnit4::class)
class KeystoreGateTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.noBackupFilesDir, "gate-test")
    private val seedKey = KeystoreDeviceKey("mespapiers.test.seed")
    private val dataKey = KeystoreDeviceKey("mespapiers.test.data")
    private val clock = AndroidClock(context)

    @Before
    fun setUp() {
        dir.deleteRecursively()
        seedKey.create()
        dataKey.create()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
        seedKey.delete()
        dataKey.delete()
    }

    private fun gate() = SessionGate(
        FileGateStore(dir), seedKey, dataKey, clock,
        newKdfParams = { PassphraseKdf.argon2id(PassphraseKdf.FLOOR_MEMORY_KIB, PassphraseKdf.FLOOR_ITERATIONS) },
    )

    @Test
    fun cle_keystore_enveloppe_et_desenveloppe() {
        val secret = ByteArray(32) { it.toByte() }
        val a = seedKey.wrap(secret)
        val b = seedKey.wrap(secret)
        assertFalse("deux enveloppes d'une même valeur diffèrent", a.contentEquals(b))
        assertArrayEquals(secret, seedKey.unwrap(a))
        assertTrue(seedKey.exists())
    }

    @Test
    fun enveloppe_d_une_autre_cle_ou_modifiee_refusee() {
        val blob = seedKey.wrap(ByteArray(32))
        for (bad in listOf({ dataKey.unwrap(blob) }, { seedKey.unwrap(blob.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }) })) {
            try {
                bad()
                fail("enveloppe acceptée à tort")
            } catch (e: DeviceKeyUnavailableException) {
            }
        }
    }

    @Test
    fun cle_supprimee_coffre_illisible_sans_recreation_silencieuse() {
        val blob = seedKey.wrap(ByteArray(32))
        seedKey.delete()
        try {
            seedKey.unwrap(blob)
            fail()
        } catch (e: DeviceKeyUnavailableException) {
        }
        assertFalse("unwrap ne doit jamais recréer une clé", seedKey.exists())
    }

    @Test
    fun double_validation_avec_le_vrai_keystore() {
        val pass = "ma phrase très secrète"
        var g = gate()
        val e = g.beginSetup(pass.toCharArray())
        val seed = Base32.decode(e.manualKey())
        fun code(offset: Long = 0) = Totp.code(seed, Totp.stepAt(clock.wallMillis() / 1000) + offset)
        assertNull(g.confirmSetup(e, code()))
        val key = g.withDataKey { it.copyOf() }
        g.lock()

        // Relance : tout repart du disque et du Keystore.
        g = gate()
        assertTrue(g.isConfigured)
        assertTrue(g.submitPassphrase("mauvaise phrase 123".toCharArray()) is PassphraseResult.Wrong)
        assertEquals(PassphraseResult.Accepted, g.submitPassphrase(pass.toCharArray()))
        assertFalse("la phrase seule n'ouvre rien", g.isOpen)
        try {
            g.withDataKey { }
            fail()
        } catch (ex: VaultLockedException) {
        }
        // Le pas courant a servi à la configuration : son code est refusé comme déjà utilisé…
        val r = g.submitTotp(code())
        if (r is TotpResult.Rejected) {
            assertEquals(TotpRejection.ALREADY_USED, r.reason)
            // …et le code du pas suivant, dans la tolérance, ouvre le coffre.
            assertEquals(TotpResult.Opened, g.submitTotp(code(1)))
        } else {
            // La fenêtre de 30 s a tourné pendant le test : le code courant est neuf.
            assertEquals(TotpResult.Opened, r)
        }
        assertArrayEquals(key, g.withDataKey { it.copyOf() })
    }

    @Test
    fun cle_keystore_perdue_signalee_sans_compter_d_echec() {
        val pass = "ma phrase très secrète"
        val g = gate()
        val e = g.beginSetup(pass.toCharArray())
        val seed = Base32.decode(e.manualKey())
        assertNull(g.confirmSetup(e, Totp.code(seed, Totp.stepAt(clock.wallMillis() / 1000))))
        g.lock()
        seedKey.delete()
        assertEquals(PassphraseResult.VaultUnreadable, gate().submitPassphrase(pass.toCharArray()))
        assertEquals(0L, gate().throttleRemainingMs())
    }

    @Test
    fun etat_d_acces_hors_des_sauvegardes_et_sans_secret() {
        val pass = "ma phrase très secrète"
        val g = gate()
        val e = g.beginSetup(pass.toCharArray())
        val seed = Base32.decode(e.manualKey())
        assertNull(g.confirmSetup(e, Totp.code(seed, Totp.stepAt(clock.wallMillis() / 1000))))
        assertTrue(dir.absolutePath.startsWith(context.noBackupFilesDir.absolutePath))
        val dump = dir.listFiles()!!.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertFalse(dump.contains(pass))
        assertFalse(dump.contains(Base32.encode(seed)))
        assertFalse(dump.contains(String(seed, Charsets.ISO_8859_1)))
    }

    @Test
    fun manifeste_installe_sans_internet_ni_sauvegarde() {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions?.toList().orEmpty()
        assertFalse(requested.contains(android.Manifest.permission.INTERNET))
        val allowed = setOf(
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.RECEIVE_BOOT_COMPLETED,
        )
        val extra = requested.filterNot { it in allowed || it.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") }
        assertEquals("permissions hors liste blanche", emptyList<String>(), extra)
        val flags = context.applicationInfo.flags
        assertEquals("allowBackup doit être désactivé", 0, flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
