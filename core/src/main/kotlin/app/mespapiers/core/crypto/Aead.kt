package app.mespapiers.core.crypto

import app.mespapiers.core.util.Bin
import app.mespapiers.core.util.Hkdf
import app.mespapiers.core.util.wipe
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Échec d'authentification : mauvaise clé, donnée modifiée ou contexte différent. */
class AeadException(message: String = "Authentification du chiffré impossible") : GeneralSecurityException(message)

/** Flux chiffré tronqué, modifié ou mal formé. */
class StreamIntegrityException(message: String) : IOException(message)

/**
 * Contexte d'un chiffré (donnée associée). Chaque composant est préfixé par sa longueur :
 * deux contextes différents ne peuvent pas produire les mêmes octets, ce qui empêche de
 * déplacer une valeur chiffrée d'une fiche ou d'un champ à l'autre.
 */
object CryptoContext {
    fun of(vararg parts: String): ByteArray {
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        out.writeInt(parts.size)
        for (p in parts) Bin.writeString(out, p)
        return bos.toByteArray()
    }
}

/**
 * AES-256-GCM pour les valeurs courtes (enregistrements, enveloppes de clés).
 * Format : version (1 octet) | nonce aléatoire (12) | chiffré + étiquette (16).
 * Un nonce aléatoire de 96 bits par message reste sûr très au-delà de la volumétrie visée
 * (limite usuelle : 2^32 messages par clé).
 */
object Aead {
    const val KEY_BYTES = 32
    private const val VERSION: Byte = 1
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also { random.nextBytes(it) }

    fun seal(key: ByteArray, plaintext: ByteArray, context: ByteArray): ByteArray {
        require(key.size == KEY_BYTES)
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(context)
        val ct = cipher.doFinal(plaintext)
        val out = ByteArray(1 + NONCE_BYTES + ct.size)
        out[0] = VERSION
        System.arraycopy(nonce, 0, out, 1, NONCE_BYTES)
        System.arraycopy(ct, 0, out, 1 + NONCE_BYTES, ct.size)
        return out
    }

    fun open(key: ByteArray, blob: ByteArray, context: ByteArray): ByteArray {
        require(key.size == KEY_BYTES)
        if (blob.size < 1 + NONCE_BYTES + TAG_BITS / 8 || blob[0] != VERSION) throw AeadException()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, blob, 1, NONCE_BYTES),
            )
            cipher.updateAAD(context)
            return cipher.doFinal(blob, 1 + NONCE_BYTES, blob.size - 1 - NONCE_BYTES)
        } catch (e: GeneralSecurityException) {
            throw AeadException()
        }
    }
}

/**
 * Chiffrement authentifié en flux pour les pièces jointes et l'archive.
 *
 * Construction « STREAM » (segments AES-256-GCM chaînés par compteur) :
 * - en-tête : magie "MPF1" | sel (16) | préfixe de nonce (7) ;
 * - clé de flux = HKDF(clé maîtresse, sel, "mp/stream/v1") : une clé par fichier ;
 * - segment i : nonce = préfixe | compteur 32 bits | drapeau « dernier » ;
 *   donnée associée = en-tête | contexte ;
 * - le drapeau « dernier » fait échouer l'authentification si le flux est tronqué,
 *   prolongé ou si des segments sont permutés.
 *
 * Chaque segment est authentifié avant d'être rendu à l'appelant. Une troncature n'est
 * détectée qu'à la fin : l'appelant ne doit activer un fichier déchiffré qu'après avoir
 * lu le flux jusqu'au bout sans erreur.
 */
object StreamCipher {
    const val SEGMENT_BYTES = 64 * 1024
    const val HEADER_BYTES = 4 + 16 + 7
    private const val TAG_BYTES = 16
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'P'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())
    private val random = SecureRandom()

    private fun streamKey(masterKey: ByteArray, salt: ByteArray) = Hkdf.derive(masterKey, salt, "mp/stream/v1")

    private fun nonce(prefix: ByteArray, counter: Long, last: Boolean): ByteArray {
        val n = ByteArray(12)
        System.arraycopy(prefix, 0, n, 0, 7)
        n[7] = (counter ushr 24).toByte()
        n[8] = (counter ushr 16).toByte()
        n[9] = (counter ushr 8).toByte()
        n[10] = counter.toByte()
        n[11] = if (last) 1 else 0
        return n
    }

    /** Longueur du chiffré pour [plainSize] octets en clair (utile pour vérifier l'espace libre). */
    fun cipherSize(plainSize: Long): Long {
        val segments = if (plainSize == 0L) 1 else (plainSize + SEGMENT_BYTES - 1) / SEGMENT_BYTES
        return HEADER_BYTES + plainSize + segments * TAG_BYTES
    }

    fun encryptingStream(masterKey: ByteArray, context: ByteArray, sink: OutputStream): OutputStream =
        Encryptor(masterKey, context, sink)

    fun decryptingStream(masterKey: ByteArray, context: ByteArray, source: InputStream): InputStream =
        Decryptor(masterKey, context, source)

    private class Encryptor(masterKey: ByteArray, context: ByteArray, private val sink: OutputStream) : OutputStream() {
        private val prefix = ByteArray(7).also { random.nextBytes(it) }
        private val key: ByteArray
        private val aad: ByteArray
        private val buf = ByteArray(SEGMENT_BYTES)
        private var fill = 0
        private var counter = 0L
        private var closed = false

        init {
            val salt = ByteArray(16).also { random.nextBytes(it) }
            key = streamKey(masterKey, salt)
            val header = MAGIC + salt + prefix
            aad = header + context
            sink.write(header)
        }

        private fun flushSegment(last: Boolean) {
            if (counter > 0xFFFFFFFFL) throw IOException("Flux trop long")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(prefix, counter, last)))
            cipher.updateAAD(aad)
            sink.write(cipher.doFinal(buf, 0, fill))
            counter++
            fill = 0
        }

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("Flux fermé")
            var o = off
            var remaining = len
            while (remaining > 0) {
                // Un segment plein n'est émis que lorsqu'un octet de plus arrive : le dernier
                // segment porte ainsi toujours le drapeau « dernier ».
                if (fill == SEGMENT_BYTES) flushSegment(last = false)
                val n = minOf(remaining, SEGMENT_BYTES - fill)
                System.arraycopy(b, o, buf, fill, n)
                fill += n
                o += n
                remaining -= n
            }
        }

        override fun flush() = sink.flush()

        override fun close() {
            if (closed) return
            closed = true
            try {
                flushSegment(last = true)
                sink.flush()
            } finally {
                key.wipe()
                buf.wipe()
                sink.close()
            }
        }
    }

    private class Decryptor(masterKey: ByteArray, context: ByteArray, private val source: InputStream) : InputStream() {
        private val prefix: ByteArray
        private val key: ByteArray
        private val aad: ByteArray
        private var current = ByteArray(SEGMENT_BYTES + TAG_BYTES)
        private var currentLen: Int
        private var next = ByteArray(SEGMENT_BYTES + TAG_BYTES)
        private var plain = ByteArray(0)
        private var plainPos = 0
        private var counter = 0L
        private var finished = false

        init {
            val header = try {
                Bin.readExactly(source, HEADER_BYTES)
            } catch (e: java.io.EOFException) {
                throw StreamIntegrityException("En-tête de flux incomplet")
            }
            if (!header.copyOfRange(0, 4).contentEquals(MAGIC)) throw StreamIntegrityException("Format de flux inconnu")
            prefix = header.copyOfRange(20, 27)
            key = streamKey(masterKey, header.copyOfRange(4, 20))
            aad = header + context
            currentLen = Bin.readUpTo(source, current, current.size)
        }

        private fun fillPlain(): Boolean {
            if (finished) return false
            if (currentLen < TAG_BYTES) throw StreamIntegrityException("Flux tronqué")
            val nextLen = if (currentLen == current.size) Bin.readUpTo(source, next, next.size) else 0
            val last = nextLen == 0
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(prefix, counter, last)))
                cipher.updateAAD(aad)
                plain.wipe()
                plain = cipher.doFinal(current, 0, currentLen)
            } catch (e: GeneralSecurityException) {
                throw StreamIntegrityException("Flux modifié, tronqué ou clé incorrecte")
            }
            plainPos = 0
            counter++
            if (last) {
                finished = true
                key.wipe()
            } else {
                val t = current
                current = next
                next = t
                currentLen = nextLen
            }
            return true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            val r = read(one, 0, 1)
            return if (r < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (plainPos >= plain.size) {
                if (!fillPlain()) return -1
            }
            val n = minOf(len, plain.size - plainPos)
            System.arraycopy(plain, plainPos, b, off, n)
            plainPos += n
            return n
        }

        override fun close() {
            key.wipe()
            plain.wipe()
            source.close()
        }
    }
}
