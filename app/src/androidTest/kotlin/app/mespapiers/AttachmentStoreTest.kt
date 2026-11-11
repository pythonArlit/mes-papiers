package app.mespapiers

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mespapiers.core.access.FileGateStore
import app.mespapiers.core.access.SessionGate
import app.mespapiers.core.access.Totp
import app.mespapiers.core.crypto.PassphraseKdf
import app.mespapiers.core.model.AttachmentRole
import app.mespapiers.core.model.Category
import app.mespapiers.core.model.FileFormat
import app.mespapiers.core.model.Ids
import app.mespapiers.core.model.ImportException
import app.mespapiers.core.model.Record
import app.mespapiers.core.util.Base32
import app.mespapiers.data.AttachmentStore
import app.mespapiers.data.Vault
import app.mespapiers.data.VaultDb
import app.mespapiers.platform.AndroidClock
import app.mespapiers.platform.ImageRenderer
import app.mespapiers.platform.KeystoreDeviceKey
import app.mespapiers.platform.PdfSession
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Pièces jointes sur appareil : stockage chiffré, nettoyage, rendu image et PDF, migration. */
@RunWith(AndroidJUnit4::class)
class AttachmentStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val gateDir = File(context.noBackupFilesDir, "gate-atttest")
    private val filesDir = File(context.noBackupFilesDir, "attachments")
    private val dbName = "vault-att-test.db"
    private val seedKey = KeystoreDeviceKey("mespapiers.atttest.seed")
    private val dataKey = KeystoreDeviceKey("mespapiers.atttest.data")
    private val clock = AndroidClock(context)
    private lateinit var gate: SessionGate
    private lateinit var db: VaultDb
    private lateinit var store: AttachmentStore
    private val recordId = Ids.new()

    private fun png(width: Int = 64, height: Int = 48): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(15, 76, 92)) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun pdf(pages: Int = 2): ByteArray {
        val doc = PdfDocument()
        repeat(pages) { i ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            page.canvas.drawColor(Color.WHITE)
            doc.finishPage(page)
        }
        return ByteArrayOutputStream().also { doc.writeTo(it); doc.close() }.toByteArray()
    }

    private fun import(data: ByteArray, name: String = "fichier", cancelled: () -> Boolean = { false }) =
        store.import(recordId, AttachmentRole.DOCUMENT, name, ByteArrayInputStream(data), data.size.toLong(), cancelled) {}

    private fun encryptedFiles() = filesDir.listFiles()?.map { it.name }?.sorted().orEmpty()

    @Before
    fun setUp() {
        gateDir.deleteRecursively()
        filesDir.deleteRecursively()
        context.deleteDatabase(dbName)
        seedKey.create()
        dataKey.create()
        gate = SessionGate(
            FileGateStore(gateDir), seedKey, dataKey, clock,
            newKdfParams = { PassphraseKdf.argon2id(PassphraseKdf.FLOOR_MEMORY_KIB, PassphraseKdf.FLOOR_ITERATIONS) },
        )
        val e = gate.beginSetup("ma phrase très secrète".toCharArray())
        assertNull(gate.confirmSetup(e, Totp.code(Base32.decode(e.manualKey()), Totp.stepAt(clock.wallMillis() / 1000))))
        db = VaultDb.open(context, dbName)
        store = AttachmentStore(context, db, gate)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
        gateDir.deleteRecursively()
        filesDir.deleteRecursively()
        seedKey.delete()
        dataKey.delete()
    }

    @Test
    fun import_relecture_et_rien_en_clair_sur_le_disque() {
        val marker = "NUMERO-SECRET-0012345".toByteArray()
        val data = pdf() + marker
        val meta = import(data, "diplome-DUPONT.pdf")
        assertEquals(FileFormat.PDF, meta.format)
        assertEquals(data.size.toLong(), meta.plainSize)
        assertArrayEquals(data, store.readAll(meta))
        assertEquals(listOf(meta), store.loadAll())

        assertEquals(listOf("${meta.id}.enc"), encryptedFiles())
        val onDisk = String(File(filesDir, "${meta.id}.enc").readBytes(), Charsets.ISO_8859_1)
        assertFalse(onDisk.contains("NUMERO-SECRET") || onDisk.contains("%PDF"))
        val dbDump = context.getDatabasePath(dbName).parentFile!!.listFiles()!!.filter { it.name.startsWith(dbName) }
            .joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertFalse("nom d'origine lisible dans la base", dbDump.contains("DUPONT"))
    }

    @Test
    fun import_refuse_ou_annule_ne_laisse_aucun_fichier() {
        try {
            import("MZ ceci est un exécutable renommé en .pdf".toByteArray())
            fail()
        } catch (e: ImportException) {
            assertEquals(ImportException.Reason.UNSUPPORTED_FORMAT, e.reason)
        }
        try {
            import(png(1200, 1200) + ByteArray(300_000), cancelled = { true })
            fail()
        } catch (e: ImportException) {
            assertEquals(ImportException.Reason.CANCELLED, e.reason)
        }
        // Verrouillage pendant l'import : abandonné proprement.
        var blocks = 0
        try {
            import(png() + ByteArray(400_000), cancelled = { if (++blocks == 2) gate.lock(); false })
            fail()
        } catch (e: ImportException) {
            assertEquals(ImportException.Reason.CANCELLED, e.reason)
        }
        assertEquals(emptyList<String>(), encryptedFiles())
        assertEquals(0, db.attachments().ids().size)
    }

    @Test
    fun suppression_et_nettoyage_des_orphelins_au_demarrage() {
        val keep = import(png(), "a.png")
        val gone = import(png(), "b.png")
        store.delete(gone)
        assertEquals(listOf("${keep.id}.enc"), encryptedFiles())

        // Arrêt brutal : un import interrompu, un fichier sans ligne, une photo de travail en clair.
        File(filesDir, "${Ids.new()}.tmp").writeBytes(ByteArray(10))
        File(filesDir, "${Ids.new()}.enc").writeBytes(ByteArray(10))
        store.captureDir.mkdirs()
        File(store.captureDir, "photo.jpg").writeBytes(ByteArray(10))
        store.viewDir.mkdirs()
        File(store.viewDir, "pdf.tmp").writeBytes(ByteArray(10))

        store.cleanup()
        assertEquals(listOf("${keep.id}.enc"), encryptedFiles())
        assertFalse(store.captureDir.exists())
        assertFalse(store.viewDir.exists())
        assertArrayEquals(png(), store.readAll(keep))
    }

    @Test
    fun copie_vers_une_autre_fiche_sous_un_nouvel_identifiant() {
        val original = import(png(), "recto.png")
        val other = Ids.new()
        val copy = store.copy(original, other)
        assertFalse(copy.id == original.id)
        assertEquals(other, copy.recordId)
        assertArrayEquals(store.readAll(original), store.readAll(copy))
        store.delete(original)
        assertArrayEquals(png(), store.readAll(copy))
    }

    @Test
    fun image_decodee_avec_dimensions_bornees() {
        val small = ImageRenderer.decode(png(64, 48))!!
        assertEquals(64 to 48, small.width to small.height)
        val large = ImageRenderer.decode(png(6000, 3000))!!
        assertTrue("${large.width}x${large.height}", large.width <= ImageRenderer.MAX_SIDE && large.height <= ImageRenderer.MAX_SIDE)
        assertNull(ImageRenderer.decode("pas une image".toByteArray()))
    }

    @Test
    fun pdf_rendu_page_par_page_sans_copie_en_clair_restante() {
        PdfSession(pdf(pages = 3), store.viewDir).use { session ->
            assertEquals(3, session.pageCount)
            val page = session.render(2)
            assertEquals(1240, page.width)
            assertTrue(page.height > page.width)
        }
        assertTrue("aucune copie en clair après fermeture", store.viewDir.listFiles().isNullOrEmpty())
        try {
            PdfSession("%PDF-1.7 tronqué".toByteArray(), store.viewDir).close()
            fail("PDF illisible accepté")
        } catch (e: Exception) {
        }
        assertTrue(store.viewDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun migration_de_la_base_version_1_vers_2_sans_perte() {
        db.close()
        context.deleteDatabase(dbName)
        // Base telle que la version 1 l'a créée : la seule table des fiches.
        val path = context.getDatabasePath(dbName)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("CREATE TABLE IF NOT EXISTS `records` (`id` TEXT NOT NULL, `blob` BLOB NOT NULL, PRIMARY KEY(`id`))")
            old.version = 1
        }
        db = VaultDb.open(context, dbName)
        val vault = Vault(db, gate)
        // Une fiche écrite « en version 1 » par le même code de chiffrement reste lisible.
        val record = Record(Ids.new(), Category.DIPLOMA, "Diplôme d'avant la migration")
        vault.put(record)
        assertEquals(listOf(record), vault.loadAll().records)
        store = AttachmentStore(context, db, gate)
        assertNotNull(import(png()))
        assertEquals(2, db.openHelper.readableDatabase.version)
    }
}
