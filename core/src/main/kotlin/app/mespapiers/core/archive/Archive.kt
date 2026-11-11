package app.mespapiers.core.archive

import app.mespapiers.core.crypto.CryptoContext
import app.mespapiers.core.crypto.KdfParams
import app.mespapiers.core.crypto.PassphraseKdf
import app.mespapiers.core.crypto.StreamCipher
import app.mespapiers.core.crypto.StreamIntegrityException
import app.mespapiers.core.model.AttachmentMeta
import app.mespapiers.core.model.Codec
import app.mespapiers.core.model.Limits
import app.mespapiers.core.model.Record
import app.mespapiers.core.util.Bin
import app.mespapiers.core.util.Hkdf
import app.mespapiers.core.util.constantTimeEquals
import app.mespapiers.core.util.wipe
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Format d'archive « MPARCH », version 1.
 *
 * En-tête en clair (authentifié comme donnée associée de tout le contenu) :
 *   "MPARCH" (6) | version (u16) | paramètres de dérivation (u16 longueur + octets) |
 *   contrôle de clé (16)
 *
 * Contenu, chiffré en flux (voir StreamCipher) sous une clé dérivée de la phrase de
 * récupération uniquement :
 *   manifeste : nombre de fiches (u32), nombre de pièces (u32), date d'export (u64),
 *               taille de chaque fiche (u32), taille de chaque pièce (u64)
 *   réglages  : octets préfixés par leur longueur
 *   fiches    : octets de chaque fiche, dans l'ordre du manifeste
 *   pièces    : métadonnées préfixées par leur longueur, puis contenu brut
 *   marqueur de fin (u32)
 *
 * L'archive ne contient ni graine TOTP, ni phrase d'accès, ni clé propre au téléphone.
 * Aucun nom ni chemin de l'archive n'est utilisé pour écrire un fichier à la restauration.
 */
object ArchiveFormat {
    val MAGIC = "MPARCH".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1
    const val END_MARK = 0x4D50454E
    const val KEY_CHECK_BYTES = 16
    const val MAX_SETTINGS_BYTES = 64 * 1024
    const val MAX_KDF_BYTES = 256

    /** Paramètres par défaut : la phrase a 140 bits d'entropie, la dérivation n'a pas à être lente. */
    fun defaultKdf(): KdfParams = PassphraseKdf.argon2id(PassphraseKdf.FLOOR_MEMORY_KIB, PassphraseKdf.FLOOR_ITERATIONS)
}

enum class ArchiveError { BAD_FORMAT, UNKNOWN_VERSION, WRONG_SECRET, CORRUPTED, LIMIT_EXCEEDED, STORAGE }

class ArchiveException(val error: ArchiveError, cause: Throwable? = null) : IOException(error.name, cause)

class AttachmentSource(val meta: AttachmentMeta, val open: () -> InputStream)

class ArchiveContent(
    val records: List<Record>,
    val attachments: List<AttachmentSource>,
    val settings: ByteArray = ByteArray(0),
    val exportedAtMs: Long = 0,
)

data class ArchiveSummary(val records: Int, val attachments: Int, val attachmentBytes: Long, val exportedAtMs: Long)

/** Reçoit le contenu vérifié dans une zone de préparation. Rien n'est activé par le lecteur. */
interface RestoreSink {
    fun settings(bytes: ByteArray)
    fun record(record: Record)

    /** [content] fournit exactement meta.plainSize octets. Le nom du fichier est choisi par l'appelant. */
    fun attachment(meta: AttachmentMeta, content: InputStream)
}

internal class ArchiveKeys(val check: ByteArray, val stream: ByteArray) {
    fun wipe() {
        check.wipe()
        stream.wipe()
    }

    companion object {
        /** [normalizedPhrase] : forme canonique donnée par RecoveryPhrase.normalize. */
        fun derive(normalizedPhrase: CharArray, params: KdfParams): ArchiveKeys {
            val master = PassphraseKdf.derive(normalizedPhrase, params)
            try {
                return ArchiveKeys(
                    Hkdf.derive(master, null, "mp/archive/check/v1", ArchiveFormat.KEY_CHECK_BYTES),
                    Hkdf.derive(master, null, "mp/archive/stream/v1"),
                )
            } finally {
                master.wipe()
            }
        }
    }
}

/**
 * Empreinte de la phrase de récupération, conservée dans le coffre chiffré pour vérifier à
 * chaque export que la phrase ressaisie est bien celle d'origine. Avec 140 bits d'entropie,
 * cette empreinte ne permet pas de retrouver la phrase.
 */
object RecoveryVerifier {
    fun compute(normalizedPhrase: CharArray, salt: ByteArray): ByteArray {
        val bytes = String(normalizedPhrase).toByteArray(Charsets.US_ASCII)
        try {
            return Hkdf.derive(bytes, salt, "mp/recovery/verifier/v1")
        } finally {
            bytes.wipe()
        }
    }

    fun matches(normalizedPhrase: CharArray, salt: ByteArray, expected: ByteArray): Boolean =
        constantTimeEquals(compute(normalizedPhrase, salt), expected)
}

object ArchiveWriter {
    /**
     * Écrit l'archive dans [out] (fermé à la fin). [onProgress] reçoit le nombre d'octets de
     * pièces jointes traités ; il peut lever une exception pour annuler, auquel cas
     * l'appelant supprime le fichier partiel.
     */
    fun write(
        out: OutputStream,
        normalizedPhrase: CharArray,
        content: ArchiveContent,
        kdf: KdfParams = ArchiveFormat.defaultKdf(),
        onProgress: (Long) -> Unit = {},
    ) {
        val keys = ArchiveKeys.derive(normalizedPhrase, kdf)
        try {
            val header = buildHeader(kdf, keys.check)
            out.write(header)
            val encodedRecords = content.records.map { Codec.encodeRecord(it) }
            DataOutputStream(StreamCipher.encryptingStream(keys.stream, bodyContext(header), out)).use { body ->
                body.writeInt(content.records.size)
                body.writeInt(content.attachments.size)
                body.writeLong(content.exportedAtMs)
                encodedRecords.forEach { body.writeInt(it.size) }
                content.attachments.forEach { body.writeLong(it.meta.plainSize) }
                Bin.writeBytes(body, content.settings)
                encodedRecords.forEach { body.write(it) }
                var done = 0L
                val buf = ByteArray(64 * 1024)
                for (a in content.attachments) {
                    Bin.writeBytes(body, Codec.encodeAttachment(a.meta))
                    var copied = 0L
                    a.open().use { src ->
                        while (true) {
                            val n = src.read(buf)
                            if (n < 0) break
                            copied += n
                            if (copied > a.meta.plainSize) throw IOException("Pièce plus longue qu'annoncé")
                            body.write(buf, 0, n)
                            done += n
                            onProgress(done)
                        }
                    }
                    if (copied != a.meta.plainSize) throw IOException("Pièce plus courte qu'annoncé")
                }
                buf.wipe()
                body.writeInt(ArchiveFormat.END_MARK)
            }
        } finally {
            keys.wipe()
        }
    }

    internal fun buildHeader(kdf: KdfParams, keyCheck: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        val h = DataOutputStream(bos)
        h.write(ArchiveFormat.MAGIC)
        h.writeShort(ArchiveFormat.VERSION)
        val k = kdf.encode()
        h.writeShort(k.size)
        h.write(k)
        h.write(keyCheck)
        return bos.toByteArray()
    }

    internal fun bodyContext(header: ByteArray): ByteArray = CryptoContext.of("mp", "archive", "v1") + header
}

object ArchiveReader {
    /**
     * Lit et vérifie l'archive entière, en remettant son contenu à [sink].
     * Toute anomalie lève [ArchiveException] : l'appelant détruit alors la zone de
     * préparation. Le contenu ne doit être activé qu'après le retour normal de cette
     * fonction, qui garantit que le flux a été authentifié jusqu'au dernier octet.
     */
    fun restore(input: InputStream, normalizedPhrase: CharArray, sink: RestoreSink): ArchiveSummary {
        val header: ByteArray
        val kdf: KdfParams
        val keyCheck: ByteArray
        try {
            val magic = Bin.readExactly(input, ArchiveFormat.MAGIC.size)
            if (!magic.contentEquals(ArchiveFormat.MAGIC)) throw ArchiveException(ArchiveError.BAD_FORMAT)
            val versionBytes = Bin.readExactly(input, 2)
            val version = ((versionBytes[0].toInt() and 0xFF) shl 8) or (versionBytes[1].toInt() and 0xFF)
            if (version > ArchiveFormat.VERSION) throw ArchiveException(ArchiveError.UNKNOWN_VERSION)
            // Les versions antérieures seront lues ici ; la version 1 est la première.
            if (version < 1) throw ArchiveException(ArchiveError.BAD_FORMAT)
            val lenBytes = Bin.readExactly(input, 2)
            val kdfLen = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
            if (kdfLen > ArchiveFormat.MAX_KDF_BYTES) throw ArchiveException(ArchiveError.BAD_FORMAT)
            val kdfBytes = Bin.readExactly(input, kdfLen)
            kdf = try {
                KdfParams.decode(kdfBytes)
            } catch (e: IOException) {
                throw ArchiveException(ArchiveError.BAD_FORMAT, e)
            }
            keyCheck = Bin.readExactly(input, ArchiveFormat.KEY_CHECK_BYTES)
            header = magic + versionBytes + lenBytes + kdfBytes + keyCheck
        } catch (e: EOFException) {
            throw ArchiveException(ArchiveError.BAD_FORMAT, e)
        }

        val keys = ArchiveKeys.derive(normalizedPhrase, kdf)
        try {
            if (!constantTimeEquals(keys.check, keyCheck)) throw ArchiveException(ArchiveError.WRONG_SECRET)
            return readBody(StreamCipher.decryptingStream(keys.stream, ArchiveWriter.bodyContext(header), input), sink)
        } catch (e: ArchiveException) {
            throw e
        } catch (e: StreamIntegrityException) {
            throw ArchiveException(ArchiveError.CORRUPTED, e)
        } catch (e: IOException) {
            throw ArchiveException(ArchiveError.CORRUPTED, e)
        } finally {
            keys.wipe()
        }
    }

    private fun readBody(plain: InputStream, sink: RestoreSink): ArchiveSummary {
        val body = DataInputStream(plain)
        val recordCount = body.readInt()
        val attachmentCount = body.readInt()
        val exportedAt = body.readLong()
        if (recordCount !in 0..Limits.MAX_RECORDS) throw ArchiveException(ArchiveError.LIMIT_EXCEEDED)
        if (attachmentCount !in 0..Limits.MAX_RECORDS * Limits.MAX_ATTACHMENTS_PER_RECORD) {
            throw ArchiveException(ArchiveError.LIMIT_EXCEEDED)
        }
        val recordSizes = IntArray(recordCount) {
            val s = body.readInt()
            if (s !in 1..Limits.MAX_RECORD_BYTES) throw ArchiveException(ArchiveError.LIMIT_EXCEEDED)
            s
        }
        var total = 0L
        val attachmentSizes = LongArray(attachmentCount) {
            val s = body.readLong()
            if (s !in 0..Limits.MAX_FILE_BYTES) throw ArchiveException(ArchiveError.LIMIT_EXCEEDED)
            total += s
            if (total > Limits.MAX_TOTAL_ATTACHMENT_BYTES) throw ArchiveException(ArchiveError.LIMIT_EXCEEDED)
            s
        }
        val settings = Bin.readBytes(body, ArchiveFormat.MAX_SETTINGS_BYTES)
        storing { sink.settings(settings) }

        val perRecord = HashMap<String, Int>(recordCount * 2)
        for (size in recordSizes) {
            val bytes = ByteArray(size)
            body.readFully(bytes)
            val record = Codec.decodeRecord(bytes)
            if (perRecord.put(record.id, 0) != null) throw ArchiveException(ArchiveError.CORRUPTED)
            storing { sink.record(record) }
        }

        val seen = HashSet<String>(attachmentCount * 2)
        for (size in attachmentSizes) {
            val meta = Codec.decodeAttachment(Bin.readBytes(body, 8 * 1024))
            if (meta.plainSize != size || !seen.add(meta.id)) throw ArchiveException(ArchiveError.CORRUPTED)
            val count = perRecord[meta.recordId] ?: throw ArchiveException(ArchiveError.CORRUPTED)
            if (count >= Limits.MAX_ATTACHMENTS_PER_RECORD) throw ArchiveException(ArchiveError.LIMIT_EXCEEDED)
            perRecord[meta.recordId] = count + 1
            val bounded = Bounded(body, size)
            storing { sink.attachment(meta, bounded) }
            bounded.drain()
        }

        if (body.readInt() != ArchiveFormat.END_MARK) throw ArchiveException(ArchiveError.CORRUPTED)
        // Lire jusqu'à la fin authentifie le dernier segment et refuse toute donnée en trop.
        if (plain.read() != -1) throw ArchiveException(ArchiveError.CORRUPTED)
        return ArchiveSummary(recordCount, attachmentCount, total, exportedAt)
    }

    /** Distingue une erreur de la zone de préparation (espace, disque) d'une archive abîmée. */
    private inline fun storing(block: () -> Unit) {
        try {
            block()
        } catch (e: ArchiveException) {
            throw e
        } catch (e: StreamIntegrityException) {
            throw e
        } catch (e: EOFException) {
            throw e
        } catch (e: IOException) {
            throw ArchiveException(ArchiveError.STORAGE, e)
        }
    }

    private class Bounded(private val src: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val r = src.read()
            if (r < 0) throw EOFException()
            remaining--
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (remaining <= 0) return -1
            val r = src.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (r < 0) throw EOFException()
            remaining -= r
            return r
        }

        fun drain() {
            val buf = ByteArray(8192)
            while (read(buf, 0, buf.size) >= 0) {
                // On jette le reste de la pièce que le récepteur n'a pas lu.
            }
        }

        override fun close() = Unit
    }
}
