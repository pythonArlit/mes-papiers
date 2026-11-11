package app.mespapiers.core.model

import app.mespapiers.core.crypto.Aead
import app.mespapiers.core.crypto.AeadException
import app.mespapiers.core.crypto.CryptoContext
import app.mespapiers.core.crypto.StreamCipher
import app.mespapiers.core.docs.FileSignature
import app.mespapiers.core.util.Bin
import app.mespapiers.core.util.Hkdf
import app.mespapiers.core.util.wipe
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class ImportException(val reason: Reason) : IOException(reason.name) {
    enum class Reason { UNSUPPORTED_FORMAT, TOO_LARGE, CANCELLED }
}

/**
 * Chiffrement des pièces jointes : métadonnées (dont le nom d'origine) en enregistrement
 * chiffré, contenu en flux. Les deux sont liés à l'identifiant de la pièce : un fichier
 * chiffré renommé d'après une autre pièce ne se déchiffre pas.
 */
object AttachmentVault {
    class Imported(val format: FileFormat, val plainSize: Long)

    /** Sous-clé dédiée aux pièces jointes. À effacer après usage. */
    fun key(dataKey: ByteArray): ByteArray = Hkdf.derive(dataKey, null, "mp/attachments/v1")

    fun sealMeta(key: ByteArray, meta: AttachmentMeta): ByteArray =
        Aead.seal(key, Codec.encodeAttachment(meta), CryptoContext.of("attachment-meta", meta.id))

    fun openMeta(key: ByteArray, id: String, blob: ByteArray): AttachmentMeta {
        val meta = Codec.decodeAttachment(Aead.open(key, blob, CryptoContext.of("attachment-meta", id)))
        if (meta.id != id) throw AeadException()
        return meta
    }

    /** Aperçu réduit d'une pièce (quelques dizaines de Ko), chiffré comme le reste. */
    fun sealThumb(key: ByteArray, id: String, jpeg: ByteArray): ByteArray =
        Aead.seal(key, jpeg, CryptoContext.of("attachment-thumb", id))

    fun openThumb(key: ByteArray, id: String, blob: ByteArray): ByteArray =
        Aead.open(key, blob, CryptoContext.of("attachment-thumb", id))

    fun encrypting(key: ByteArray, id: String, sink: OutputStream): OutputStream =
        StreamCipher.encryptingStream(key, CryptoContext.of("attachment-file", id), sink)

    fun decrypting(key: ByteArray, id: String, source: InputStream): InputStream =
        StreamCipher.decryptingStream(key, CryptoContext.of("attachment-file", id), source)

    /**
     * Importe [source] en flux vers [sink] (fermé à la fin, même en cas d'erreur).
     * Le format est reconnu par la signature du fichier, la taille est bornée pendant la
     * copie, et [isCancelled] est consulté à chaque bloc. En cas d'exception, l'appelant
     * supprime le fichier partiel : rien n'est encore référencé.
     */
    fun importStream(
        key: ByteArray,
        id: String,
        source: InputStream,
        sink: OutputStream,
        maxBytes: Long = Limits.MAX_FILE_BYTES,
        isCancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {},
    ): Imported {
        val out = encrypting(key, id, sink)
        val buf = ByteArray(64 * 1024)
        try {
            val head = ByteArray(FileSignature.PROBE_BYTES)
            val headLen = Bin.readUpTo(source, head, head.size)
            val format = FileSignature.detect(head.copyOf(headLen)) ?: throw ImportException(ImportException.Reason.UNSUPPORTED_FORMAT)
            out.write(head, 0, headLen)
            var total = headLen.toLong()
            while (true) {
                if (isCancelled()) throw ImportException(ImportException.Reason.CANCELLED)
                val n = source.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw ImportException(ImportException.Reason.TOO_LARGE)
                out.write(buf, 0, n)
                onProgress(total)
            }
            return Imported(format, total)
        } finally {
            buf.wipe()
            out.close()
        }
    }
}
