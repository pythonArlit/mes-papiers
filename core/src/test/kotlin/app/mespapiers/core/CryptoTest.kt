package app.mespapiers.core

import app.mespapiers.core.crypto.Aead
import app.mespapiers.core.crypto.AeadException
import app.mespapiers.core.crypto.CryptoContext
import app.mespapiers.core.crypto.KdfAlgo
import app.mespapiers.core.crypto.KdfParams
import app.mespapiers.core.crypto.PassphraseKdf
import app.mespapiers.core.crypto.StreamCipher
import app.mespapiers.core.crypto.StreamIntegrityException
import app.mespapiers.core.util.Base32
import app.mespapiers.core.util.Hkdf
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Random

class CryptoTest {

    // ------------------------------------------------------------ primitives

    @Test
    fun hkdf_rfc5869_vecteur1() {
        val okm = Hkdf.expand(
            Hkdf.extract(hex("000102030405060708090a0b0c"), ByteArray(22) { 0x0b }),
            hex("f0f1f2f3f4f5f6f7f8f9"),
            42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.toHex(),
        )
    }

    @Test
    fun base32_rfc4648() {
        assertEquals("MZXW6YTBOI", Base32.encode("foobar".toByteArray()))
        assertEquals("MZXW6YQ", Base32.encode("foob".toByteArray()))
        assertArrayEquals("foobar".toByteArray(), Base32.decode("mzxw 6ytb oi"))
        val seed = ByteArray(20) { (it * 13).toByte() }
        assertArrayEquals(seed, Base32.decode(Base32.encode(seed)))
    }

    @Test
    fun argon2id_rfc9106_vecteur() {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(ByteArray(16) { 2 })
            .withSecret(ByteArray(8) { 3 })
            .withAdditional(ByteArray(12) { 4 })
            .withMemoryAsKB(32).withIterations(3).withParallelism(4)
            .build()
        val out = ByteArray(32)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(ByteArray(32) { 1 }, out)
        assertEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659", out.toHex())
    }

    @Test
    fun pbkdf2_vecteur_connu() {
        val p = KdfParams(KdfAlgo.PBKDF2_SHA256, 1, "saltsaltsaltsalt".toByteArray(), 0, 2, 1)
        val a = PassphraseKdf.derive("password".toCharArray(), p)
        assertEquals(32, a.size)
        // Vecteur de référence PBKDF2-HMAC-SHA256 ("password", "salt", 1 itération), sans validate().
        val spec = javax.crypto.spec.PBEKeySpec("password".toCharArray(), "salt".toByteArray(), 1, 256)
        val ref = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b", ref.toHex())
    }

    @Test
    fun derivation_deterministe_et_sensible_au_sel_et_a_la_phrase() {
        val p = fastKdf()
        val a = PassphraseKdf.derive("une phrase secrète".toCharArray(), p)
        assertArrayEquals(a, PassphraseKdf.derive("une phrase secrète".toCharArray(), p))
        assertFalse(a.contentEquals(PassphraseKdf.derive("une phrase secrete".toCharArray(), p)))
        assertFalse(a.contentEquals(PassphraseKdf.derive("une phrase secrète".toCharArray(), fastKdf())))
    }

    @Test
    fun derivation_identique_pour_deux_formes_unicode() {
        val p = fastKdf()
        val composed = "café du matin".toCharArray()
        val decomposed = "café du matin".toCharArray()
        assertArrayEquals(PassphraseKdf.derive(composed, p), PassphraseKdf.derive(decomposed, p))
    }

    @Test
    fun parametres_kdf_aller_retour_et_bornes() {
        val p = PassphraseKdf.argon2id()
        val q = KdfParams.decode(p.encode())
        assertEquals(p.algo, q.algo)
        assertEquals(p.memoryKiB, q.memoryKiB)
        assertEquals(p.iterations, q.iterations)
        assertArrayEquals(p.salt, q.salt)
        val huge = KdfParams(KdfAlgo.ARGON2ID, 1, ByteArray(16), 4_000_000, 3, 1).encode()
        try {
            KdfParams.decode(huge)
            fail("des paramètres démesurés doivent être refusés")
        } catch (e: IOException) {
        }
        assertTrue(PassphraseKdf.pbkdf2().iterations >= 600_000)
    }

    @Test
    fun calibrage_reste_au_dessus_du_plancher() {
        // Horloge simulée : chaque mesure « dure » le temps indiqué, quel que soit le réglage.
        fun fakeTimer(msPerMeasure: Long): () -> Long {
            var t = 0L
            return { t += msPerMeasure * 1_000_000 / 2; t }
        }
        // Appareil lent : on reste au plancher, jamais en dessous.
        val slow = PassphraseKdf.calibrate(memoryKiB = 64, nanoTime = fakeTimer(1200))
        assertEquals(PassphraseKdf.FLOOR_ITERATIONS, slow.iterations)
        // Appareil rapide : le nombre de passes monte, dans la limite fixée.
        val fast = PassphraseKdf.calibrate(memoryKiB = 64, nanoTime = fakeTimer(100))
        assertEquals(8, fast.iterations)
    }

    // ------------------------------------------------------------ AEAD

    @Test
    fun aead_aller_retour_et_nonces_distincts() {
        val key = Aead.newKey()
        val ctx = CryptoContext.of("record", "abc", "title")
        val a = Aead.seal(key, "Passeport".toByteArray(), ctx)
        val b = Aead.seal(key, "Passeport".toByteArray(), ctx)
        assertFalse(a.contentEquals(b))
        assertEquals("Passeport", String(Aead.open(key, a, ctx)))
    }

    @Test
    fun aead_refuse_un_autre_contexte_une_autre_cle_ou_une_modification() {
        val key = Aead.newKey()
        val blob = Aead.seal(key, "0012345".toByteArray(), CryptoContext.of("record", "fiche-1", "number"))
        fun refused(block: () -> Unit) = try {
            block(); fail("aurait dû être refusé")
        } catch (e: AeadException) {
        }
        // Permutation vers un autre champ ou une autre fiche.
        refused { Aead.open(key, blob, CryptoContext.of("record", "fiche-1", "iban")) }
        refused { Aead.open(key, blob, CryptoContext.of("record", "fiche-2", "number")) }
        refused { Aead.open(Aead.newKey(), blob, CryptoContext.of("record", "fiche-1", "number")) }
        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        refused { Aead.open(key, tampered, CryptoContext.of("record", "fiche-1", "number")) }
        refused { Aead.open(key, ByteArray(5), CryptoContext.of("x")) }
    }

    @Test
    fun contexte_sans_ambiguite() {
        assertFalse(CryptoContext.of("ab", "c").contentEquals(CryptoContext.of("a", "bc")))
    }

    // ------------------------------------------------------------ flux

    private fun encrypt(key: ByteArray, ctx: ByteArray, data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        StreamCipher.encryptingStream(key, ctx, bos).use { out ->
            // Écritures de tailles irrégulières pour traverser les frontières de segment.
            var off = 0
            val r = Random(1)
            while (off < data.size) {
                val n = minOf(data.size - off, 1 + r.nextInt(50_000))
                out.write(data, off, n)
                off += n
            }
        }
        return bos.toByteArray()
    }

    private fun decrypt(key: ByteArray, ctx: ByteArray, blob: ByteArray): ByteArray =
        StreamCipher.decryptingStream(key, ctx, ByteArrayInputStream(blob)).use { it.readBytes() }

    @Test
    fun flux_aller_retour_pour_toutes_les_tailles_limites() {
        val key = Aead.newKey()
        val ctx = CryptoContext.of("file", "id")
        val seg = StreamCipher.SEGMENT_BYTES
        for (size in listOf(0, 1, seg - 1, seg, seg + 1, 2 * seg, 3 * seg + 17)) {
            val data = ByteArray(size).also { Random(size.toLong()).nextBytes(it) }
            val blob = encrypt(key, ctx, data)
            assertEquals("taille chiffrée pour $size", StreamCipher.cipherSize(size.toLong()), blob.size.toLong())
            assertArrayEquals("aller-retour pour $size", data, decrypt(key, ctx, blob))
        }
    }

    @Test
    fun flux_ne_laisse_pas_le_clair_dans_le_chiffre() {
        val key = Aead.newKey()
        val marker = "NUMERO-DE-PASSEPORT-18AB12345".toByteArray()
        val blob = encrypt(key, CryptoContext.of("f"), ByteArray(1000) + marker + ByteArray(1000))
        assertFalse(String(blob, Charsets.ISO_8859_1).contains(String(marker, Charsets.ISO_8859_1)))
    }

    private fun assertStreamRefused(key: ByteArray, ctx: ByteArray, blob: ByteArray, what: String) {
        try {
            decrypt(key, ctx, blob)
            fail("flux accepté à tort : $what")
        } catch (e: StreamIntegrityException) {
        }
    }

    @Test
    fun flux_refuse_troncature_modification_permutation_et_mauvais_contexte() {
        val key = Aead.newKey()
        val ctx = CryptoContext.of("file", "A")
        val seg = StreamCipher.SEGMENT_BYTES
        val data = ByteArray(3 * seg).also { Random(9).nextBytes(it) }
        val blob = encrypt(key, ctx, data)
        val h = StreamCipher.HEADER_BYTES
        val cseg = seg + 16

        assertStreamRefused(key, ctx, blob.copyOf(blob.size - 1), "dernier octet retiré")
        assertStreamRefused(key, ctx, blob.copyOf(h + 2 * cseg), "tronqué à une frontière de segment")
        assertStreamRefused(key, ctx, blob.copyOf(h + cseg), "tronqué après le premier segment")
        assertStreamRefused(key, ctx, blob.copyOf(h), "en-tête seul")
        assertStreamRefused(key, ctx, blob.copyOf(10), "en-tête incomplet")
        assertStreamRefused(key, ctx, blob + ByteArray(1), "octet ajouté")
        assertStreamRefused(key, ctx, blob + blob.copyOfRange(h, h + cseg), "segment ajouté")

        val flipped = blob.copyOf().also { it[h + cseg + 5] = (it[h + cseg + 5].toInt() xor 0x40).toByte() }
        assertStreamRefused(key, ctx, flipped, "octet modifié")

        val swapped = blob.copyOf()
        System.arraycopy(blob, h, swapped, h + cseg, cseg)
        System.arraycopy(blob, h + cseg, swapped, h, cseg)
        assertStreamRefused(key, ctx, swapped, "segments permutés")

        val headerTouched = blob.copyOf().also { it[6] = (it[6].toInt() xor 1).toByte() }
        assertStreamRefused(key, ctx, headerTouched, "sel modifié")

        assertStreamRefused(key, CryptoContext.of("file", "B"), blob, "autre contexte")
        assertStreamRefused(Aead.newKey(), ctx, blob, "autre clé")
    }

    @Test
    fun flux_deux_chiffrements_du_meme_fichier_different() {
        val key = Aead.newKey()
        val data = ByteArray(100) { 7 }
        assertFalse(encrypt(key, CryptoContext.of("f"), data).contentEquals(encrypt(key, CryptoContext.of("f"), data)))
    }
}
