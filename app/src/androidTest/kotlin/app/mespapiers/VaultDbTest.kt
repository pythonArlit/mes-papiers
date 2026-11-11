package app.mespapiers

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mespapiers.core.access.FileGateStore
import app.mespapiers.core.access.SessionGate
import app.mespapiers.core.access.Totp
import app.mespapiers.core.access.VaultLockedException
import app.mespapiers.core.crypto.PassphraseKdf
import app.mespapiers.core.model.Category
import app.mespapiers.core.model.Field
import app.mespapiers.core.model.FieldType
import app.mespapiers.core.model.Ids
import app.mespapiers.core.model.Record
import app.mespapiers.core.util.Base32
import app.mespapiers.data.RecordRow
import app.mespapiers.data.Vault
import app.mespapiers.data.VaultDb
import app.mespapiers.platform.AndroidClock
import app.mespapiers.platform.KeystoreDeviceKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/** Fiches dans Room, avec le vrai Keystore : rien de lisible dans les fichiers de la base. */
@RunWith(AndroidJUnit4::class)
class VaultDbTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.noBackupFilesDir, "gate-dbtest")
    private val dbName = "vault-test.db"
    private val seedKey = KeystoreDeviceKey("mespapiers.dbtest.seed")
    private val dataKey = KeystoreDeviceKey("mespapiers.dbtest.data")
    private val clock = AndroidClock(context)
    private lateinit var gate: SessionGate
    private lateinit var db: VaultDb
    private lateinit var vault: Vault

    private val passport = Record(
        Ids.new(), Category.IDENTITY, "Passeport de test", tags = listOf("voyage"), favorite = true,
        fields = listOf(Field("number", FieldType.IDENTIFIER, "00AB12345", sensitive = true)),
        expiry = LocalDate.of(2031, 5, 1),
    )
    private val rib = Record(
        Ids.new(), Category.BANK, "RIB de test",
        fields = listOf(Field("account_number", FieldType.IDENTIFIER, "000123456789", sensitive = true)),
    )

    @Before
    fun setUp() {
        dir.deleteRecursively()
        context.deleteDatabase(dbName)
        seedKey.create()
        dataKey.create()
        gate = SessionGate(
            FileGateStore(dir), seedKey, dataKey, clock,
            newKdfParams = { PassphraseKdf.argon2id(PassphraseKdf.FLOOR_MEMORY_KIB, PassphraseKdf.FLOOR_ITERATIONS) },
        )
        val e = gate.beginSetup("ma phrase très secrète".toCharArray())
        val seed = Base32.decode(e.manualKey())
        assertEquals(null, gate.confirmSetup(e, Totp.code(seed, Totp.stepAt(clock.wallMillis() / 1000))))
        db = Room.databaseBuilder(context, VaultDb::class.java, dbName).build()
        vault = Vault(db, gate)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
        dir.deleteRecursively()
        seedKey.delete()
        dataKey.delete()
    }

    @Test
    fun ajout_modification_suppression_apres_reouverture_de_la_base() {
        vault.put(passport)
        vault.put(rib)
        db.close()
        db = Room.databaseBuilder(context, VaultDb::class.java, dbName).build()
        vault = Vault(db, gate)

        val loaded = vault.loadAll()
        assertEquals(0, loaded.unreadable)
        assertEquals(setOf(passport, rib), loaded.records.toSet())
        assertEquals("zéros initiaux conservés", "000123456789", loaded.records.first { it.id == rib.id }.fields[0].value)

        vault.put(passport.copy(title = "Passeport renouvelé", favorite = false))
        vault.delete(rib.id)
        val after = vault.loadAll().records
        assertEquals(1, after.size)
        assertEquals("Passeport renouvelé", after[0].title)
    }

    @Test
    fun aucune_donnee_en_clair_dans_les_fichiers_de_la_base() {
        vault.put(passport)
        vault.put(rib)
        // Base, journal d'écriture anticipée et fichier de mémoire partagée.
        val files = context.getDatabasePath(dbName).parentFile!!.listFiles()!!.filter { it.name.startsWith(dbName) }
        assertTrue(files.isNotEmpty())
        val dump = files.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        for (secret in listOf("Passeport", "00AB12345", "000123456789", "RIB de test", "voyage", "2031-05-01")) {
            assertFalse("« $secret » lisible dans ${files.map { it.name }}", dump.contains(secret))
        }
    }

    @Test
    fun coffre_verrouille_aucune_fiche_accessible() {
        vault.put(passport)
        gate.lock()
        for (op in listOf({ vault.loadAll(); Unit }, { vault.put(rib) }, { vault.delete(passport.id) })) {
            try {
                op()
                fail("opération acceptée coffre verrouillé")
            } catch (e: VaultLockedException) {
            }
        }
        assertEquals("rien n'a été supprimé", 1, db.records().count())
    }

    @Test
    fun ligne_abimee_ou_deplacee_comptee_et_non_affichee() {
        vault.put(passport)
        vault.put(rib)
        val rows = db.records().all().associateBy { it.id }
        // Le chiffré du RIB recopié sous l'identifiant du passeport.
        db.records().put(RecordRow(passport.id, rows.getValue(rib.id).blob))
        val loaded = vault.loadAll()
        assertEquals(1, loaded.unreadable)
        assertEquals(listOf(rib), loaded.records)
    }
}
