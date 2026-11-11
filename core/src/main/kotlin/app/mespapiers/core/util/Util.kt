package app.mespapiers.core.util

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Efface un tampon sensible. Sur la JVM, c'est un effort raisonnable, pas une garantie. */
fun ByteArray.wipe() = fill(0)

fun CharArray.wipe() = fill('\u0000')

fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

/**
 * Horloge injectable.
 * - [wallMillis] : heure murale, modifiable par l'utilisateur (sert au TOTP et aux dates).
 * - [elapsedMillis] : temps monotone depuis le démarrage du téléphone, insensible aux
 *   changements d'heure (sert aux délais de sécurité).
 * - [bootCount] : compteur de démarrages, pour détecter qu'un redémarrage a remis
 *   [elapsedMillis] à zéro.
 */
interface Clock {
    fun wallMillis(): Long
    fun elapsedMillis(): Long
    fun bootCount(): Int
}

/** Base32 RFC 4648 sans remplissage, pour la graine TOTP. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun decode(text: String): ByteArray {
        val clean = text.trim().trimEnd('=').replace(" ", "").uppercase()
        val out = ByteArray(clean.length * 5 / 8)
        var buffer = 0
        var bits = 0
        var pos = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "Caractère Base32 invalide" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out[pos++] = ((buffer shr (bits - 8)) and 0xFF).toByte()
                bits -= 8
            }
        }
        return out
    }
}

/** HKDF-SHA256 (RFC 5869). */
object Hkdf {
    private const val ALGO = "HmacSHA256"

    fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALGO)
        mac.init(SecretKeySpec(if (salt == null || salt.isEmpty()) ByteArray(32) else salt, ALGO))
        return mac.doFinal(ikm)
    }

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val mac = Mac.getInstance(ALGO)
        mac.init(SecretKeySpec(prk, ALGO))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        t.wipe()
        return out
    }

    fun derive(ikm: ByteArray, salt: ByteArray?, info: String, length: Int = 32): ByteArray {
        val prk = extract(salt, ikm)
        try {
            return expand(prk, info.toByteArray(Charsets.UTF_8), length)
        } finally {
            prk.wipe()
        }
    }
}

/** Lecture/écriture binaire à longueurs préfixées et bornées. */
object Bin {
    const val MAX_STRING_BYTES = 64 * 1024

    fun writeBytes(out: DataOutputStream, b: ByteArray) {
        out.writeInt(b.size)
        out.write(b)
    }

    fun readBytes(inp: DataInputStream, max: Int): ByteArray {
        val n = inp.readInt()
        if (n < 0 || n > max) throw java.io.IOException("Longueur hors limite")
        val b = ByteArray(n)
        inp.readFully(b)
        return b
    }

    fun writeString(out: DataOutputStream, s: String) = writeBytes(out, s.toByteArray(Charsets.UTF_8))

    fun readString(inp: DataInputStream, max: Int = MAX_STRING_BYTES): String =
        String(readBytes(inp, max), Charsets.UTF_8)

    /** Lit exactement [n] octets ou lève [EOFException]. */
    fun readExactly(inp: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(b, off, n - off)
            if (r < 0) throw EOFException()
            off += r
        }
        return b
    }

    /** Lit jusqu'à [n] octets ; renvoie le nombre lu (inférieur à [n] seulement en fin de flux). */
    fun readUpTo(inp: InputStream, b: ByteArray, n: Int): Int {
        var off = 0
        while (off < n) {
            val r = inp.read(b, off, n - off)
            if (r < 0) break
            off += r
        }
        return off
    }
}
