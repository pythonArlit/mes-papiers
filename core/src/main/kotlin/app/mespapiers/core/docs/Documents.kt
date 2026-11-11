package app.mespapiers.core.docs

import app.mespapiers.core.model.FileFormat
import app.mespapiers.core.model.Record
import java.math.BigInteger
import java.security.SecureRandom
import java.text.Normalizer

/** Recherche insensible à la casse et aux accents. */
object TextNormalizer {
    private val MARKS = Regex("\\p{M}+")

    fun normalize(text: String): String =
        MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")
            .lowercase()
            .replace("œ", "oe")
            .replace("æ", "ae")
            .trim()
}

/**
 * Index de recherche en mémoire, construit après déverrouillage et vidé au verrouillage.
 * Il porte sur le titre, le libellé de catégorie et les étiquettes, jamais sur les champs.
 */
class SearchIndex {
    private class Entry(val id: String, val haystack: String)

    private var entries: List<Entry> = emptyList()

    fun rebuild(records: Collection<Record>, categoryLabel: (Record) -> String) {
        entries = records.map { r ->
            Entry(r.id, TextNormalizer.normalize(listOf(r.title, categoryLabel(r), r.tags.joinToString(" ")).joinToString(" ")))
        }
    }

    /** Identifiants des fiches contenant tous les mots de [query]. Une requête vide renvoie tout. */
    fun search(query: String): List<String> {
        val words = TextNormalizer.normalize(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        return entries.filter { e -> words.all { e.haystack.contains(it) } }.map { it.id }
    }

    fun clear() {
        entries = emptyList()
    }
}

/**
 * Contrôles de format. Ils ne renvoient jamais qu'un avertissement : la saisie n'est pas
 * bloquée, car les formats varient selon les pays.
 */
object FormatChecks {
    enum class Warning { IBAN_FORMAT, IBAN_CHECKSUM, RIB_UNUSUAL, IDENTIFIER_UNUSUAL }

    private fun compact(s: String) = s.filterNot { it.isWhitespace() || it == '-' || it == '.' }

    fun iban(value: String): Warning? {
        val v = compact(value).uppercase()
        if (v.isEmpty()) return null
        if (v.length !in 15..34 || !v.all { it in 'A'..'Z' || it in '0'..'9' } ||
            !v[0].isLetter() || !v[1].isLetter() || !v[2].isDigit() || !v[3].isDigit()
        ) {
            return Warning.IBAN_FORMAT
        }
        val rearranged = v.substring(4) + v.substring(0, 4)
        val digits = buildString { for (c in rearranged) if (c.isDigit()) append(c) else append(c - 'A' + 10) }
        return if (BigInteger(digits).mod(BigInteger.valueOf(97)).toInt() == 1) null else Warning.IBAN_CHECKSUM
    }

    /** RIB : seules la longueur et la nature des caractères sont examinées. */
    fun rib(value: String): Warning? {
        val v = compact(value)
        if (v.isEmpty()) return null
        return if (v.length !in 10..34 || !v.all { it.isLetterOrDigit() }) Warning.RIB_UNUSUAL else null
    }

    fun identifier(value: String): Warning? {
        if (value.isEmpty()) return null
        return if (value.any { it.isISOControl() } || value.length > 64) Warning.IDENTIFIER_UNUSUAL else null
    }
}

/** Reconnaissance du format réel par la signature du fichier, jamais par l'extension. */
object FileSignature {
    const val PROBE_BYTES = 16

    fun detect(head: ByteArray): FileFormat? {
        fun at(i: Int) = if (i < head.size) head[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> FileFormat.JPEG
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 &&
                at(4) == 0x0D && at(5) == 0x0A && at(6) == 0x1A && at(7) == 0x0A -> FileFormat.PNG
            at(0) == 0x25 && at(1) == 0x50 && at(2) == 0x44 && at(3) == 0x46 && at(4) == 0x2D -> FileFormat.PDF
            else -> null
        }
    }
}

/** Indicateur de robustesse : une aide, pas une mesure. Aucune règle de composition n'est imposée. */
object PassphraseStrength {
    enum class Level { TOO_SHORT, WEAK, FAIR, GOOD, STRONG }

    fun estimate(passphrase: CharSequence): Level {
        val length = Character.codePointCount(passphrase, 0, passphrase.length)
        if (length < 12) return Level.TOO_SHORT
        val distinct = passphrase.toSet().size
        var classes = 0
        if (passphrase.any { it.isLowerCase() }) classes++
        if (passphrase.any { it.isUpperCase() }) classes++
        if (passphrase.any { it.isDigit() }) classes++
        if (passphrase.any { !it.isLetterOrDigit() }) classes++
        val words = passphrase.trim().split(Regex("\\s+")).size
        // Suites évidentes : caractères répétés ou consécutifs (aaaa, 1234, abcd).
        var monotone = 0
        for (i in 1 until passphrase.length) {
            val d = passphrase[i] - passphrase[i - 1]
            if (d == 0 || d == 1 || d == -1) monotone++
        }
        val monotoneRatio = monotone.toDouble() / (passphrase.length - 1).coerceAtLeast(1)
        if (distinct < 6 || monotoneRatio > 0.6) return Level.WEAK
        var score = 0
        if (length >= 16) score++
        if (length >= 22) score++
        if (classes >= 3 || words >= 4) score++
        if (distinct >= 12) score++
        return when {
            score >= 4 -> Level.STRONG
            score == 3 -> Level.GOOD
            score >= 1 -> Level.FAIR
            else -> Level.WEAK
        }
    }
}

/**
 * Phrase de récupération : 28 caractères tirés au hasard dans un alphabet de 32 symboles
 * sans caractères ambigus (I, L, O, U exclus), soit 140 bits, en sept groupes de quatre.
 */
object RecoveryPhrase {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 28
    const val GROUP = 4

    fun generate(random: SecureRandom = SecureRandom()): CharArray {
        val raw = CharArray(LENGTH) { ALPHABET[random.nextInt(32)] }
        return format(raw)
    }

    fun format(normalized: CharArray): CharArray {
        val out = CharArray(LENGTH + LENGTH / GROUP - 1)
        var o = 0
        for (i in normalized.indices) {
            if (i > 0 && i % GROUP == 0) out[o++] = '-'
            out[o++] = normalized[i]
        }
        return out
    }

    /**
     * Forme canonique : majuscules, sans séparateur, O lu comme 0, I et L lus comme 1.
     * Renvoie null si la saisie n'a pas la bonne longueur ou contient un symbole inconnu.
     */
    fun normalize(input: CharArray): CharArray? {
        val out = CharArray(LENGTH)
        var n = 0
        for (c in input) {
            if (c == '-' || c.isWhitespace()) continue
            val u = when (val up = c.uppercaseChar()) {
                'O' -> '0'
                'I', 'L' -> '1'
                else -> up
            }
            if (ALPHABET.indexOf(u) < 0 || n >= LENGTH) {
                out.fill('\u0000')
                return null
            }
            out[n++] = u
        }
        if (n != LENGTH) {
            out.fill('\u0000')
            return null
        }
        return out
    }
}
