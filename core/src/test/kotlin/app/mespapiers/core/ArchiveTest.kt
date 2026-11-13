package app.mespapiers.core

import app.mespapiers.core.archive.ArchiveContent
import app.mespapiers.core.archive.ArchiveError
import app.mespapiers.core.archive.ArchiveException
import app.mespapiers.core.archive.ArchiveReader
import app.mespapiers.core.archive.ArchiveSummary
import app.mespapiers.core.archive.ArchiveWriter
import app.mespapiers.core.archive.AttachmentSource
import app.mespapiers.core.archive.RecoveryVerifier
import app.mespapiers.core.archive.RestoreSink
import app.mespapiers.core.docs.RecoveryPhrase
import app.mespapiers.core.model.AttachmentMeta
import app.mespapiers.core.model.AttachmentRole
import app.mespapiers.core.model.Category
import app.mespapiers.core.model.Field
import app.mespapiers.core.model.FieldType
import app.mespapiers.core.model.FileFormat
import app.mespapiers.core.model.Ids
import app.mespapiers.core.model.Limits
import app.mespapiers.core.model.Record
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import java.util.Random

class ArchiveTest {

    /**
     * Zone de préparation en mémoire. Comme l'application, elle ne devient « active » que si
     * la restauration se termine sans erreur ; sinon elle est vidée.
     */
    private class Staging : RestoreSink {
        val records = ArrayList<Record>()
        val files = LinkedHashMap<String, ByteArray>()
        val metas = ArrayList<AttachmentMeta>()
        var settings = ByteArray(0)
        var failOnAttachment: IOException? = null

        override fun settings(bytes: ByteArray) {
            settings = bytes
        }

        override fun record(record: Record) {
            records += record
        }

        override fun attachment(meta: AttachmentMeta, content: InputStream) {
            failOnAttachment?.let { throw it }
            metas += meta
            // Le nom du fichier vient de l'installation, jamais de l'archive.
            files["staging-${files.size}.bin"] = content.readBytes()
        }

        fun clear() {
            records.clear(); files.clear(); metas.clear(); settings = ByteArray(0)
        }
    }

    /** Reproduit la bascule atomique : renvoie le contenu activé, ou rien du tout. */
    private fun restoreOrEmpty(archive: ByteArray, phrase: CharArray): Pair<Staging, ArchiveError?> {
        val staging = Staging()
        return try {
            ArchiveReader.restore(ByteArrayInputStream(archive), phrase, staging)
            staging to null
        } catch (e: ArchiveException) {
            staging.clear()
            staging to e.error
        }
    }

    private val phrase = RecoveryPhrase.normalize(RecoveryPhrase.generate())!!
    private val kdf = fastKdf()

    private val passport = Record(
        Ids.new(), Category.IDENTITY, "Passeport", tags = listOf("voyage"), favorite = true,
        fields = listOf(Field("number", FieldType.IDENTIFIER, "00AB12345", sensitive = true)),
        expiry = LocalDate.of(2030, 5, 1),
    )
    private val diploma = Record(Ids.new(), Category.DIPLOMA, "Diplôme de Master")
    private val rib = Record(
        Ids.new(), Category.BANK, "RIB",
        fields = listOf(Field("account_number", FieldType.IDENTIFIER, "000123456789", sensitive = true)),
    )

    private fun bytes(n: Int, seed: Long) = ByteArray(n).also { Random(seed).nextBytes(it) }

    private val front = bytes(150_000, 1)
    private val back = bytes(64 * 1024, 2)
    private val pdf = "%PDF-1.7 contenu".toByteArray()
    private val empty = ByteArray(0)

    private fun source(recordId: String, role: AttachmentRole, format: FileFormat, name: String, data: ByteArray) =
        AttachmentSource(AttachmentMeta(Ids.new(), recordId, role, format, name, data.size.toLong())) { ByteArrayInputStream(data) }

    private fun content() = ArchiveContent(
        records = listOf(passport, diploma, rib),
        attachments = listOf(
            source(passport.id, AttachmentRole.FRONT, FileFormat.JPEG, "recto.jpg", front),
            source(passport.id, AttachmentRole.BACK, FileFormat.JPEG, "../../verso.jpg", back),
            source(diploma.id, AttachmentRole.DOCUMENT, FileFormat.PDF, "diplome.pdf", pdf),
            source(rib.id, AttachmentRole.PROOF, FileFormat.PDF, "vide.pdf", empty),
        ),
        settings = "rappels:30,7,1@9".toByteArray(),
        exportedAtMs = 1_800_000_000_000,
    )

    private fun export(c: ArchiveContent = content(), p: CharArray = phrase): ByteArray {
        val bos = ByteArrayOutputStream()
        ArchiveWriter.write(bos, p, c, kdf)
        return bos.toByteArray()
    }

    @Test
    fun export_puis_restauration_sur_une_autre_installation() {
        val archive = export()
        val staging = Staging()
        // Aucune clé du téléphone n'intervient : seule la phrase de récupération est fournie.
        val summary = ArchiveReader.restore(ByteArrayInputStream(archive), phrase, staging)
        assertEquals(ArchiveSummary(3, 4, (front.size + back.size + pdf.size).toLong(), 1_800_000_000_000), summary)
        assertEquals(listOf(passport, diploma, rib), staging.records)
        assertEquals("000123456789", staging.records[2].fields[0].value)
        assertTrue(staging.records[0].favorite)
        assertEquals("rappels:30,7,1@9", String(staging.settings))
        val files = staging.files.values.toList()
        assertArrayEquals(front, files[0])
        assertArrayEquals(back, files[1])
        assertArrayEquals(pdf, files[2])
        assertArrayEquals(empty, files[3])
        assertEquals(listOf(passport.id, passport.id, diploma.id, rib.id), staging.metas.map { it.recordId })
        // Le nom d'origine n'est qu'un libellé : il n'a servi à nommer aucun fichier.
        assertEquals("../../verso.jpg", staging.metas[1].originalName)
        assertTrue(staging.files.keys.all { it.startsWith("staging-") })
    }

    @Test
    fun archive_vide_et_coffre_sans_piece() {
        val (s, err) = restoreOrEmpty(export(ArchiveContent(emptyList(), emptyList())), phrase)
        assertEquals(null, err)
        assertEquals(0, s.records.size)
        val (s2, err2) = restoreOrEmpty(export(ArchiveContent(listOf(diploma), emptyList())), phrase)
        assertEquals(null, err2)
        assertEquals(listOf(diploma), s2.records)
    }

    @Test
    fun archive_ne_contient_rien_en_clair() {
        val text = String(export(), Charsets.ISO_8859_1)
        for (secret in listOf("Passeport", "00AB12345", "000123456789", "recto.jpg", "rappels", "%PDF", "Dipl")) {
            assertFalse("« $secret » lisible dans l'archive", text.contains(secret))
        }
        assertTrue(text.startsWith("MPARCH"))
    }

    @Test
    fun mauvaise_phrase_installation_toujours_vide() {
        val other = RecoveryPhrase.normalize(RecoveryPhrase.generate())!!
        val (staging, err) = restoreOrEmpty(export(), other)
        assertEquals(ArchiveError.WRONG_SECRET, err)
        assertTrue(staging.records.isEmpty() && staging.files.isEmpty())
    }

    @Test
    fun archive_modifiee_refusee_a_chaque_position_echantillonnee() {
        val archive = export()
        // Un bit inversé tous les 997 octets, plus le début et la fin : jamais d'activation.
        val positions = (0 until archive.size step 997) + listOf(0, 7, 8, 30, archive.size - 1, archive.size - 17)
        for (pos in positions) {
            val tampered = archive.copyOf().also { it[pos] = (it[pos].toInt() xor 0x04).toByte() }
            val (staging, err) = restoreOrEmpty(tampered, phrase)
            assertTrue("position $pos acceptée", err != null)
            assertTrue(staging.records.isEmpty() && staging.files.isEmpty())
        }
    }

    @Test
    fun archive_tronquee_refusee_a_toutes_les_longueurs_echantillonnees() {
        val archive = export()
        val lengths = (0 until archive.size step 1009) + listOf(1, 6, 8, 40, archive.size - 1, archive.size - 16, archive.size - 17)
        for (len in lengths) {
            val (staging, err) = restoreOrEmpty(archive.copyOf(len), phrase)
            assertTrue("longueur $len acceptée", err != null)
            assertTrue(staging.records.isEmpty() && staging.files.isEmpty())
        }
        val (_, extra) = restoreOrEmpty(archive + ByteArray(1), phrase)
        assertEquals(ArchiveError.CORRUPTED, extra)
    }

    @Test
    fun version_inconnue_et_format_etranger_refuses_clairement() {
        val archive = export()
        val future = archive.copyOf().also { it[7] = 2 }
        assertEquals(ArchiveError.UNKNOWN_VERSION, restoreOrEmpty(future, phrase).second)
        val zero = archive.copyOf().also { it[7] = 0 }
        assertEquals(ArchiveError.BAD_FORMAT, restoreOrEmpty(zero, phrase).second)
        assertEquals(ArchiveError.BAD_FORMAT, restoreOrEmpty("%PDF-1.7 pas une archive".toByteArray(), phrase).second)
        assertEquals(ArchiveError.BAD_FORMAT, restoreOrEmpty(ByteArray(0), phrase).second)
    }

    @Test
    fun espace_insuffisant_signale_comme_tel_et_rien_n_est_active() {
        val staging = Staging().apply { failOnAttachment = IOException("ENOSPC") }
        try {
            ArchiveReader.restore(ByteArrayInputStream(export()), phrase, staging)
            fail()
        } catch (e: ArchiveException) {
            assertEquals(ArchiveError.STORAGE, e.error)
        }
    }

    @Test
    fun limites_de_taille_appliquees_a_l_export_et_a_la_restauration() {
        // Une pièce qui annonce plus que la limite par fichier est refusée dès le manifeste,
        // avant qu'un seul octet de contenu ne soit écrit dans la zone de préparation.
        val tooBig = Limits.MAX_FILE_BYTES + 1
        val meta = AttachmentMeta(Ids.new(), diploma.id, AttachmentRole.DOCUMENT, FileFormat.PDF, "gros.pdf", tooBig)
        val c = ArchiveContent(listOf(diploma), listOf(AttachmentSource(meta) { ZeroStream(tooBig) }))
        val (staging, err) = restoreOrEmpty(export(c), phrase)
        assertEquals(ArchiveError.LIMIT_EXCEEDED, err)
        assertTrue(staging.files.isEmpty())

        // Plus de dix pièces sur une fiche.
        val many = ArchiveContent(listOf(diploma), (1..11).map { source(diploma.id, AttachmentRole.OTHER, FileFormat.PNG, "p$it.png", ByteArray(3)) })
        assertEquals(ArchiveError.LIMIT_EXCEEDED, restoreOrEmpty(export(many), phrase).second)

        // Pièce rattachée à une fiche absente de l'archive.
        val orphan = ArchiveContent(listOf(diploma), listOf(source(passport.id, AttachmentRole.FRONT, FileFormat.PNG, "x.png", ByteArray(3))))
        assertEquals(ArchiveError.CORRUPTED, restoreOrEmpty(export(orphan), phrase).second)

        // Deux fiches de même identifiant.
        val dup = ArchiveContent(listOf(diploma, diploma), emptyList())
        assertEquals(ArchiveError.CORRUPTED, restoreOrEmpty(export(dup), phrase).second)
    }

    @Test
    fun export_refuse_une_piece_dont_la_taille_ne_correspond_pas() {
        val meta = AttachmentMeta(Ids.new(), diploma.id, AttachmentRole.DOCUMENT, FileFormat.PDF, "a.pdf", 10)
        for (actual in listOf(9, 11)) {
            try {
                export(ArchiveContent(listOf(diploma), listOf(AttachmentSource(meta) { ByteArrayInputStream(ByteArray(actual)) })))
                fail("taille $actual acceptée pour 10 annoncés")
            } catch (e: IOException) {
            }
        }
    }

    @Test
    fun export_annulable_et_progression() {
        var last = 0L
        val bos = ByteArrayOutputStream()
        ArchiveWriter.write(bos, phrase, content(), kdf) { last = it }
        assertEquals((front.size + back.size + pdf.size).toLong(), last)
        try {
            ArchiveWriter.write(ByteArrayOutputStream(), phrase, content(), kdf) { if (it > 100_000) throw InterruptedException("annulé") }
            fail()
        } catch (e: InterruptedException) {
        }
    }

    @Test
    fun deux_exports_identiques_donnent_des_fichiers_differents() {
        assertFalse(export().contentEquals(export()))
    }

    @Test
    fun empreinte_de_la_phrase_de_recuperation() {
        val salt = ByteArray(16) { 5 }
        val v = RecoveryVerifier.compute(phrase, salt)
        assertTrue(RecoveryVerifier.matches(phrase, salt, v))
        val other = RecoveryPhrase.normalize(RecoveryPhrase.generate())!!
        assertFalse(RecoveryVerifier.matches(other, salt, v))
        assertFalse(String(v, Charsets.ISO_8859_1).contains(String(phrase)))
    }

    @Test
    fun trace_chiffree_de_la_phrase_de_recuperation() {
        val dk = app.mespapiers.core.crypto.Aead.newKey()
        val blob = app.mespapiers.core.archive.RecoveryRecord.seal(dk, phrase)
        assertTrue(app.mespapiers.core.archive.RecoveryRecord.matches(dk, blob, phrase))
        assertFalse(app.mespapiers.core.archive.RecoveryRecord.matches(dk, blob, RecoveryPhrase.normalize(RecoveryPhrase.generate())!!))
        assertFalse("autre coffre", app.mespapiers.core.archive.RecoveryRecord.matches(app.mespapiers.core.crypto.Aead.newKey(), blob, phrase))
        assertFalse(app.mespapiers.core.archive.RecoveryRecord.matches(dk, ByteArray(10), phrase))
        assertFalse(String(blob, Charsets.ISO_8859_1).contains(String(phrase)))
    }

    /** Flux de zéros de longueur donnée, pour tester les limites sans allouer 20 Mo. */
    private class ZeroStream(private var remaining: Long) : InputStream() {
        override fun read(): Int = if (remaining-- > 0) 0 else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = minOf(len.toLong(), remaining).toInt()
            java.util.Arrays.fill(b, off, off + n, 0)
            remaining -= n
            return n
        }
    }
}
