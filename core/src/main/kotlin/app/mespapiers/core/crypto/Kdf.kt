package app.mespapiers.core.crypto

import app.mespapiers.core.util.Bin
import app.mespapiers.core.util.wipe
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.CharBuffer
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class KdfAlgo(val id: Int) {
    ARGON2ID(1),
    PBKDF2_SHA256(2),
    ;

    companion object {
        fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: throw IOException("Dérivation inconnue")
    }
}

/**
 * Paramètres de dérivation, versionnés et stockés en clair à côté du chiffré
 * (ils ne sont pas secrets ; voir MENACES.md, « données lisibles hors session »).
 */
class KdfParams(
    val algo: KdfAlgo,
    val version: Int,
    val salt: ByteArray,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    fun encode(): ByteArray {
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        out.writeByte(algo.id)
        out.writeByte(version)
        Bin.writeBytes(out, salt)
        out.writeInt(memoryKiB)
        out.writeInt(iterations)
        out.writeInt(parallelism)
        return bos.toByteArray()
    }

    companion object {
        const val CURRENT_VERSION = 1

        // Bornes appliquées aux paramètres lus depuis un fichier (archive notamment), pour
        // qu'un fichier malveillant ne puisse pas imposer une dérivation démesurée.
        const val MAX_MEMORY_KIB = 262_144
        const val MAX_ARGON_ITERATIONS = 16
        const val MAX_PBKDF2_ITERATIONS = 5_000_000
        const val MIN_PBKDF2_ITERATIONS = 600_000

        fun decode(bytes: ByteArray): KdfParams {
            val inp = DataInputStream(ByteArrayInputStream(bytes))
            val algo = KdfAlgo.fromId(inp.readUnsignedByte())
            val version = inp.readUnsignedByte()
            if (version != CURRENT_VERSION) throw IOException("Version de dérivation inconnue")
            val salt = Bin.readBytes(inp, 64)
            val p = KdfParams(algo, version, salt, inp.readInt(), inp.readInt(), inp.readInt())
            p.validate()
            return p
        }
    }

    fun validate() {
        if (salt.size < 16) throw IOException("Sel trop court")
        when (algo) {
            KdfAlgo.ARGON2ID -> {
                if (memoryKiB !in 8..MAX_MEMORY_KIB || iterations !in 1..MAX_ARGON_ITERATIONS || parallelism !in 1..4) {
                    throw IOException("Paramètres Argon2id hors limites")
                }
            }
            KdfAlgo.PBKDF2_SHA256 -> {
                if (iterations !in 1..MAX_PBKDF2_ITERATIONS) throw IOException("Paramètres PBKDF2 hors limites")
            }
        }
    }
}

/**
 * Dérivation d'une clé de 256 bits à partir d'une phrase.
 * Argon2id est fourni par Bouncy Castle (bibliothèque maintenue, pur JVM, donc testable hors
 * appareil) ; le repli PBKDF2-HMAC-SHA256 vient du fournisseur de la plateforme.
 */
object PassphraseKdf {
    const val OUTPUT_BYTES = 32
    private val random = SecureRandom()

    /** Valeurs de départ ; [calibrate] ajuste les itérations sur l'appareil. */
    const val DEFAULT_MEMORY_KIB = 32 * 1024
    const val DEFAULT_ITERATIONS = 3

    /** Plancher : jamais en dessous de la recommandation OWASP (19 Mio, 2 passes). */
    const val FLOOR_MEMORY_KIB = 19 * 1024
    const val FLOOR_ITERATIONS = 2

    fun newSalt(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

    fun argon2id(memoryKiB: Int = DEFAULT_MEMORY_KIB, iterations: Int = DEFAULT_ITERATIONS) =
        KdfParams(KdfAlgo.ARGON2ID, KdfParams.CURRENT_VERSION, newSalt(), memoryKiB, iterations, 1)

    fun pbkdf2(iterations: Int = KdfParams.MIN_PBKDF2_ITERATIONS) =
        KdfParams(KdfAlgo.PBKDF2_SHA256, KdfParams.CURRENT_VERSION, newSalt(), 0, iterations, 1)

    /**
     * Normalise la phrase en NFC puis l'encode en UTF-8, pour qu'un même texte saisi avec
     * deux claviers différents donne la même clé. La normalisation crée une chaîne
     * transitoire non effaçable (limite documentée dans MENACES.md).
     */
    private fun encode(passphrase: CharArray): ByteArray {
        val normalized = Normalizer.normalize(CharBuffer.wrap(passphrase), Normalizer.Form.NFC)
        return normalized.toByteArray(Charsets.UTF_8)
    }

    fun derive(passphrase: CharArray, params: KdfParams): ByteArray {
        params.validate()
        return when (params.algo) {
            KdfAlgo.ARGON2ID -> {
                val pwd = encode(passphrase)
                try {
                    val p = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                        .withSalt(params.salt)
                        .withMemoryAsKB(params.memoryKiB)
                        .withIterations(params.iterations)
                        .withParallelism(params.parallelism)
                        .build()
                    val gen = Argon2BytesGenerator()
                    gen.init(p)
                    val out = ByteArray(OUTPUT_BYTES)
                    gen.generateBytes(pwd, out)
                    out
                } finally {
                    pwd.wipe()
                }
            }
            KdfAlgo.PBKDF2_SHA256 -> {
                val normalized = Normalizer.normalize(CharBuffer.wrap(passphrase), Normalizer.Form.NFC).toCharArray()
                val spec = PBEKeySpec(normalized, params.salt, params.iterations, OUTPUT_BYTES * 8)
                try {
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
                } finally {
                    spec.clearPassword()
                    normalized.wipe()
                }
            }
        }
    }

    /**
     * Choisit le nombre de passes Argon2id pour viser [targetMinMs]..[targetMaxMs] sur
     * l'appareil courant, sans descendre sous le plancher. [nanoTime] est injectable.
     * Si la mémoire demandée n'est pas disponible, l'appelant réessaie avec
     * [FLOOR_MEMORY_KIB], puis bascule sur PBKDF2 (voir DECISIONS.md).
     */
    fun calibrate(
        memoryKiB: Int = DEFAULT_MEMORY_KIB,
        targetMinMs: Long = 500,
        targetMaxMs: Long = 1000,
        maxIterations: Int = 8,
        nanoTime: () -> Long = System::nanoTime,
    ): KdfParams {
        val probe = CharArray(16) { 'x' }
        var iterations = FLOOR_ITERATIONS
        while (true) {
            val params = argon2id(memoryKiB, iterations)
            val start = nanoTime()
            derive(probe, params).wipe()
            val ms = (nanoTime() - start) / 1_000_000
            if (ms >= targetMinMs || iterations >= maxIterations) return argon2id(memoryKiB, iterations)
            // Estimation linéaire du nombre de passes pour atteindre le milieu de la cible.
            val perIteration = maxOf(1L, ms / iterations)
            val wanted = ((targetMinMs + targetMaxMs) / 2 / perIteration).toInt()
            iterations = wanted.coerceIn(iterations + 1, maxIterations)
        }
    }
}
