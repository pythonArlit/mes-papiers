package app.mespapiers.core

import app.mespapiers.core.crypto.Aead
import app.mespapiers.core.crypto.AeadException
import app.mespapiers.core.crypto.StreamIntegrityException
import app.mespapiers.core.model.AttachmentMeta
import app.mespapiers.core.model.AttachmentRole
import app.mespapiers.core.model.AttachmentVault
import app.mespapiers.core.model.FileFormat
import app.mespapiers.core.model.Ids
import app.mespapiers.core.model.ImportException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random

class AttachmentTest {
    private val key = AttachmentVault.key(Aead.newKey())
    private val jpeg = hex("ffd8ffe000104a464946") + ByteArray(200_000).also { Random(3).nextBytes(it) }
    private val pdf = "%PDF-1.7\nNUMERO-SECRET-0012345\n%%EOF".toByteArray()

    private fun import(id: String, data: ByteArray, max: Long = 20L * 1024 * 1024, cancelAfter: Int = Int.MAX_VALUE): Pair<AttachmentVault.Imported, ByteArray> {
        val sink = ByteArrayOutputStream()
        var blocks = 0
        val result = AttachmentVault.importStream(key, id, ByteArrayInputStream(data), sink, max, isCancelled = { blocks++ >= cancelAfter })
        return result to sink.toByteArray()
    }

    private fun reasonOf(block: () -> Unit): ImportException.Reason = try {
        block()
        throw AssertionError("import accepté à tort")
    } catch (e: ImportException) {
        e.reason
    }

    @Test
    fun import_photo_et_pdf_puis_relecture() {
        val id = Ids.new()
        val (result, blob) = import(id, jpeg)
        assertEquals(FileFormat.JPEG, result.format)
        assertEquals(jpeg.size.toLong(), result.plainSize)
        assertArrayEquals(jpeg, AttachmentVault.decrypting(key, id, ByteArrayInputStream(blob)).use { it.readBytes() })

        val id2 = Ids.new()
        val (r2, blob2) = import(id2, pdf)
        assertEquals(FileFormat.PDF, r2.format)
        assertFalse(String(blob2, Charsets.ISO_8859_1).contains("NUMERO-SECRET"))
        assertArrayEquals(pdf, AttachmentVault.decrypting(key, id2, ByteArrayInputStream(blob2)).use { it.readBytes() })
    }

    @Test
    fun format_reel_verifie_par_la_signature() {
        // Un exécutable ou une archive renommés en .pdf ou .jpg sont refusés.
        assertEquals(ImportException.Reason.UNSUPPORTED_FORMAT, reasonOf { import(Ids.new(), hex("4d5a90000300000004000000ffff0000")) })
        assertEquals(ImportException.Reason.UNSUPPORTED_FORMAT, reasonOf { import(Ids.new(), hex("504b030414000000")) })
        assertEquals(ImportException.Reason.UNSUPPORTED_FORMAT, reasonOf { import(Ids.new(), ByteArray(0)) })
    }

    @Test
    fun taille_limite_appliquee_pendant_la_copie() {
        assertEquals(ImportException.Reason.TOO_LARGE, reasonOf { import(Ids.new(), jpeg, max = 100_000) })
        // Exactement à la limite : accepté.
        assertEquals(jpeg.size.toLong(), import(Ids.new(), jpeg, max = jpeg.size.toLong()).first.plainSize)
    }

    @Test
    fun import_annule() {
        assertEquals(ImportException.Reason.CANCELLED, reasonOf { import(Ids.new(), jpeg, cancelAfter = 1) })
    }

    @Test
    fun fichier_lie_a_l_identifiant_de_la_piece() {
        val a = Ids.new()
        val (_, blob) = import(a, pdf)
        try {
            AttachmentVault.decrypting(key, Ids.new(), ByteArrayInputStream(blob)).use { it.readBytes() }
            fail("fichier déchiffré sous un autre identifiant")
        } catch (e: StreamIntegrityException) {
        }
    }

    @Test
    fun metadonnees_chiffrees_nom_d_origine_compris() {
        val meta = AttachmentMeta(Ids.new(), Ids.new(), AttachmentRole.FRONT, FileFormat.JPEG, "passeport-recto-DUPONT.jpg", 1234)
        val blob = AttachmentVault.sealMeta(key, meta)
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("DUPONT"))
        assertEquals(meta, AttachmentVault.openMeta(key, meta.id, blob))
        try {
            AttachmentVault.openMeta(key, Ids.new(), blob)
            fail()
        } catch (e: AeadException) {
        }
        assertTrue(key.size == 32)
    }
}
