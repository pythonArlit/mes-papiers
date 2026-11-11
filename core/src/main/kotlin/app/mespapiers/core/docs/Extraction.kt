package app.mespapiers.core.docs

import app.mespapiers.core.model.Category
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Ce qui a été reconnu dans le texte d'un document scanné. Tout est une PROPOSITION :
 * l'utilisateur relit et corrige avant d'enregistrer. Rien n'est jamais enregistré seul.
 */
data class Extraction(
    val category: Category? = null,
    /** Nature du document, en clair (« Passeport », « Baccalauréat »…). Sert aussi de titre proposé. */
    val docType: String? = null,
    val holder: String? = null,
    val number: String? = null,
    val issuer: String? = null,
    val issuedOn: LocalDate? = null,
    val expiresOn: LocalDate? = null,
    val birthDate: LocalDate? = null,
    val obtainedOn: LocalDate? = null,
    val institution: String? = null,
    val bank: String? = null,
    val iban: String? = null,
    val bic: String? = null,
    /** Vrai si les données viennent d'une zone lisible par machine dont les clés de contrôle sont justes. */
    val fromMrz: Boolean = false,
) {
    val isEmpty: Boolean
        get() = this == Extraction(category = category)

    /** Valeurs proposées pour les champs du gabarit de [category] (clé du champ → valeur, dates en ISO). */
    fun valuesFor(category: Category): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun put(key: String, value: Any?) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) out[key] = text
        }
        when (category) {
            Category.IDENTITY -> {
                put("type", docType); put("holder", holder); put("number", number)
                put("issuer", issuer); put("issued_on", issuedOn); put("expires_on", expiresOn)
            }
            Category.DIPLOMA -> {
                put("title", docType); put("institution", institution)
                put("obtained_on", obtainedOn ?: issuedOn); put("reference", number)
            }
            Category.CIVIL_STATUS -> {
                put("act_type", docType); put("holder", holder); put("act_date", issuedOn); put("reference", number)
            }
            Category.ADDRESS -> {
                put("label", docType); put("valid_from", issuedOn); put("valid_until", expiresOn)
            }
            Category.BANK -> {
                put("bank", bank); put("holder", holder); put("iban", iban); put("bic", bic)
            }
            Category.OTHER -> {
                put("reference", number); put("valid_until", expiresOn)
            }
        }
        return out
    }
}

/** Zone lisible par machine (ICAO 9303) des passeports et cartes d'identité. */
object Mrz {
    class Data(
        val docCode: String,
        val issuer: String,
        val number: String,
        val surname: String,
        val givenNames: String,
        val birth: LocalDate?,
        val expiry: LocalDate?,
    )

    private val CANDIDATE = Regex("^[A-Z0-9<]{28,46}$")

    private fun value(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'Z' -> c - 'A' + 10
        else -> 0
    }

    /** Clé de contrôle ICAO : pondérations 7, 3, 1. */
    fun checkDigit(field: String): Int {
        val weights = intArrayOf(7, 3, 1)
        var sum = 0
        field.forEachIndexed { i, c -> sum += value(c) * weights[i % 3] }
        return sum % 10
    }

    private fun checks(field: String, digit: Char): Boolean = digit in '0'..'9' && checkDigit(field) == digit - '0'

    /** Dans un champ numérique, l'OCR confond souvent lettres et chiffres. */
    private fun digits(s: String): String = buildString {
        for (c in s) append(
            when (c) {
                'O', 'D', 'Q' -> '0'
                'I', 'L' -> '1'
                'Z' -> '2'
                'S' -> '5'
                'G' -> '6'
                'B' -> '8'
                else -> c
            },
        )
    }

    private fun letters(s: String): String = buildString {
        for (c in s) append(
            when (c) {
                '0' -> 'O'
                '1' -> 'I'
                '5' -> 'S'
                '8' -> 'B'
                else -> c
            },
        )
    }

    private fun date(yymmdd: String, century: (Int) -> Int): LocalDate? {
        if (yymmdd.length != 6 || !yymmdd.all { it.isDigit() }) return null
        val yy = yymmdd.substring(0, 2).toInt()
        return try {
            LocalDate.of(century(yy) + yy, yymmdd.substring(2, 4).toInt(), yymmdd.substring(4, 6).toInt())
        } catch (e: DateTimeException) {
            null
        }
    }

    private fun names(field: String): Pair<String, String> {
        val clean = letters(field)
        val parts = clean.split("<<", limit = 2)
        fun words(s: String) = s.replace('<', ' ').trim().replace(Regex("\\s+"), " ")
        return words(parts[0]) to words(parts.getOrElse(1) { "" })
    }

    private fun build(code: String, issuer: String, numberRaw: String, numberCheck: Char, birthRaw: String, birthCheck: Char, expiryRaw: String, expiryCheck: Char, nameField: String, currentYear: Int): Data? {
        val birthDigits = digits(birthRaw)
        val expiryDigits = digits(expiryRaw)
        val numberOk = checks(numberRaw, digits(numberCheck.toString())[0])
        val birthOk = checks(birthDigits, digits(birthCheck.toString())[0])
        val expiryOk = checks(expiryDigits, digits(expiryCheck.toString())[0])
        // Au moins deux clés de contrôle justes : sinon ce n'est pas une zone fiable.
        if (listOf(numberOk, birthOk, expiryOk).count { it } < 2) return null
        val (surname, given) = names(nameField)
        val shortYear = currentYear % 100
        return Data(
            docCode = code.replace("<", ""),
            issuer = letters(issuer).replace("<", ""),
            number = if (numberOk) numberRaw.replace("<", "") else "",
            surname = surname,
            givenNames = given,
            birth = if (birthOk) date(birthDigits) { yy -> if (yy > shortYear) 1900 else 2000 } else null,
            expiry = if (expiryOk) date(expiryDigits) { 2000 } else null,
        )
    }

    /** Cherche une zone lisible par machine dans les lignes reconnues. Null si aucune n'est fiable. */
    fun find(lines: List<String>, currentYear: Int): Data? {
        val c = lines.map { it.replace(" ", "").uppercase().replace('«', '<').replace('‹', '<') }.filter { CANDIDATE.matches(it) && it.contains('<') || CANDIDATE.matches(it) && it.count { ch -> ch.isDigit() } >= 10 }
        fun pad(s: String, n: Int) = s.padEnd(n, '<').take(n)
        for (i in c.indices) {
            // Passeport (TD3) : deux lignes de 44 caractères. L'OCR perd souvent les chevrons de fin de la première.
            if (i + 1 < c.size && c[i].startsWith("P") && c[i].length in 30..46 && c[i + 1].length in 40..46) {
                val l1 = pad(c[i], 44)
                val l2 = pad(c[i + 1], 44)
                build(l1.substring(0, 2), l1.substring(2, 5), l2.substring(0, 9), l2[9], l2.substring(13, 19), l2[19], l2.substring(21, 27), l2[27], l1.substring(5), currentYear)?.let { return it }
            }
            // Carte (TD1) : trois lignes de 30 caractères.
            if (i + 2 < c.size && c[i].length in 28..31 && c[i + 1].length in 28..31 && c[i + 2].length in 26..31) {
                val l1 = pad(c[i], 30)
                val l2 = pad(c[i + 1], 30)
                val l3 = pad(c[i + 2], 30)
                build(l1.substring(0, 2), l1.substring(2, 5), l1.substring(5, 14), l1[14], l2.substring(0, 6), l2[6], l2.substring(8, 14), l2[14], l3, currentYear)?.let { return it }
            }
            // Carte ou visa (TD2) : deux lignes de 36 caractères.
            if (i + 1 < c.size && c[i].length in 34..37 && c[i + 1].length in 34..37) {
                val l1 = pad(c[i], 36)
                val l2 = pad(c[i + 1], 36)
                build(l1.substring(0, 2), l1.substring(2, 5), l2.substring(0, 9), l2[9], l2.substring(13, 19), l2[19], l2.substring(21, 27), l2[27], l1.substring(5), currentYear)?.let { return it }
            }
        }
        return null
    }
}

/**
 * Lecture d'un texte reconnu par OCR : nature du document, dates, numéros, titulaire,
 * coordonnées bancaires. Ce sont des heuristiques : elles proposent, l'utilisateur dispose.
 */
object DocumentReader {
    private class Kind(val keyword: String, val category: Category, val label: String)

    // Du plus précis au plus général : le premier mot-clé trouvé l'emporte.
    private val KINDS = listOf(
        Kind("releve d identite bancaire", Category.BANK, "Relevé d'identité bancaire"),
        Kind("carte nationale d identite", Category.IDENTITY, "Carte nationale d'identité"),
        Kind("carte d identite", Category.IDENTITY, "Carte d'identité"),
        Kind("identity card", Category.IDENTITY, "Carte d'identité"),
        Kind("permis de conduire", Category.IDENTITY, "Permis de conduire"),
        Kind("titre de sejour", Category.IDENTITY, "Titre de séjour"),
        Kind("carte consulaire", Category.IDENTITY, "Carte consulaire"),
        Kind("carte d electeur", Category.IDENTITY, "Carte d'électeur"),
        Kind("passeport", Category.IDENTITY, "Passeport"),
        Kind("passport", Category.IDENTITY, "Passeport"),
        Kind("extrait d acte de naissance", Category.CIVIL_STATUS, "Extrait d'acte de naissance"),
        Kind("acte de naissance", Category.CIVIL_STATUS, "Acte de naissance"),
        Kind("acte de mariage", Category.CIVIL_STATUS, "Acte de mariage"),
        Kind("acte de deces", Category.CIVIL_STATUS, "Acte de décès"),
        Kind("jugement suppletif", Category.CIVIL_STATUS, "Jugement supplétif"),
        Kind("certificat de nationalite", Category.CIVIL_STATUS, "Certificat de nationalité"),
        Kind("livret de famille", Category.CIVIL_STATUS, "Livret de famille"),
        Kind("certificat de residence", Category.ADDRESS, "Certificat de résidence"),
        Kind("attestation d hebergement", Category.ADDRESS, "Attestation d'hébergement"),
        Kind("justificatif de domicile", Category.ADDRESS, "Justificatif de domicile"),
        Kind("quittance de loyer", Category.ADDRESS, "Quittance de loyer"),
        Kind("attestation de reussite", Category.DIPLOMA, "Attestation de réussite"),
        Kind("releve de notes", Category.DIPLOMA, "Relevé de notes"),
        Kind("baccalaureat", Category.DIPLOMA, "Baccalauréat"),
        Kind("doctorat", Category.DIPLOMA, "Doctorat"),
        Kind("master", Category.DIPLOMA, "Master"),
        Kind("licence", Category.DIPLOMA, "Licence"),
        Kind("brevet", Category.DIPLOMA, "Brevet"),
        Kind("diplome", Category.DIPLOMA, "Diplôme"),
        Kind("certificat", Category.DIPLOMA, "Certificat"),
        Kind("attestation", Category.DIPLOMA, "Attestation"),
        Kind("police d assurance", Category.OTHER, "Assurance"),
        Kind("assurance", Category.OTHER, "Assurance"),
        Kind("contrat", Category.OTHER, "Contrat"),
        Kind("facture", Category.ADDRESS, "Facture"),
    )

    private val COUNTRIES = mapOf(
        "NER" to "Niger", "NGA" to "Nigeria", "BEN" to "Bénin", "BFA" to "Burkina Faso", "MLI" to "Mali",
        "TCD" to "Tchad", "DZA" to "Algérie", "LBY" to "Libye", "SEN" to "Sénégal", "CIV" to "Côte d'Ivoire",
        "TGO" to "Togo", "GHA" to "Ghana", "CMR" to "Cameroun", "MAR" to "Maroc", "TUN" to "Tunisie",
        "MRT" to "Mauritanie", "GIN" to "Guinée", "FRA" to "France", "DEU" to "Allemagne", "D" to "Allemagne",
        "BEL" to "Belgique", "CHE" to "Suisse", "CAN" to "Canada", "USA" to "États-Unis",
        "GBR" to "Royaume-Uni", "ESP" to "Espagne", "ITA" to "Italie",
    )

    private val MONTHS = listOf(
        listOf("janvier", "janv", "jan", "january"), listOf("fevrier", "fevr", "fev", "feb", "february"),
        listOf("mars", "mar", "march"), listOf("avril", "avr", "apr", "april"), listOf("mai", "may"),
        listOf("juin", "jun", "june"), listOf("juillet", "juil", "jul", "july"), listOf("aout", "aug", "august"),
        listOf("septembre", "sept", "sep", "september"), listOf("octobre", "oct", "october"),
        listOf("novembre", "nov", "november"), listOf("decembre", "dec", "december"),
    )

    private val NUMERIC_DATE = Regex("(?<!\\d)(\\d{1,2})\\s?[./-]\\s?(\\d{1,2})\\s?[./-]\\s?(\\d{4})(?!\\d)")
    private val TEXT_DATE = Regex("(?<!\\d)(\\d{1,2})(?:er)?\\s+([a-z]{3,9})\\.?\\s+(\\d{4})(?!\\d)")
    // Le libellé est insensible à la casse ; la valeur n'est faite que de majuscules et de chiffres.
    private val NUMBER = Regex("(?:\\bN\\s?[°º]\\s?|\\b[Nn][Oo]\\b\\.?|\\b(?i:num[ée]ro)\\b|\\b(?i:number)\\b)\\s*[:.]?\\s*([A-Z0-9]+(?:[ /-][A-Z0-9]+){0,3})")
    private val IBAN = Regex("[A-Z]{2}\\d{2}(?: ?[A-Z0-9]){11,30}")
    private val BIC = Regex("(?:BIC|SWIFT)[^A-Z0-9]{0,12}([A-Z]{4}[A-Z]{2}[A-Z0-9]{2}(?:[A-Z0-9]{3})?)(?![A-Z0-9])")

    private val EXPIRY_WORDS = listOf("expir", "valable jusqu", "valid until", "validite", "echeance", "date of expiry")
    private val ISSUED_WORDS = listOf("delivr", "issue", "emis", "etabli", "fait a", "fait le")
    private val BIRTH_WORDS = listOf(" ne le", " nee le", " ne e le", "naissance", "birth", "born")
    private val OBTAINED_WORDS = listOf("session", "obtenu", "soutenu", "admis")

    /** Minuscules, sans accents, ponctuation réduite à des espaces. */
    private fun flat(s: String): String = TextNormalizer.normalize(s).replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? =
        if (year !in 1900..2100) null else try {
            LocalDate.of(year, month, day)
        } catch (e: DateTimeException) {
            null
        }

    private class Found(val date: LocalDate, val context: String)

    private val ALL_WORDS = EXPIRY_WORDS + ISSUED_WORDS + BIRTH_WORDS + OBTAINED_WORDS

    /** Contexte d'une date : ce qui la précède sur sa ligne ; à défaut de libellé, la ligne d'avant aussi. */
    private fun contextOf(previous: String?, prefix: String): String {
        val same = " " + flat(prefix)
        return if (ALL_WORDS.any { same.contains(it) }) same else " " + flat((previous ?: "") + " " + prefix)
    }

    private fun dates(lines: List<String>): List<Found> {
        val out = ArrayList<Found>()
        lines.forEachIndexed { i, line ->
            val previous = lines.getOrNull(i - 1)
            for (m in NUMERIC_DATE.findAll(line)) {
                safeDate(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())
                    ?.let { out += Found(it, contextOf(previous, line.substring(0, m.range.first))) }
            }
            val lower = TextNormalizer.normalize(line)
            for (m in TEXT_DATE.findAll(lower)) {
                val month = MONTHS.indexOfFirst { names -> m.groupValues[2] in names } + 1
                if (month > 0) {
                    safeDate(m.groupValues[3].toInt(), month, m.groupValues[1].toInt())
                        ?.let { out += Found(it, contextOf(previous, lower.substring(0, m.range.first))) }
                }
            }
        }
        return out
    }

    private fun valueAfterLabel(lines: List<String>, labels: List<String>): String? {
        lines.forEachIndexed { i, line ->
            val f = flat(line)
            val label = labels.firstOrNull { f == it || f.startsWith("$it ") } ?: return@forEachIndexed
            // Valeur sur la même ligne (après « : » ou après le libellé), sinon sur la ligne suivante.
            val same = line.substringAfter(':', "").trim().ifEmpty {
                val words = line.trim().split(Regex("\\s+"))
                words.drop(label.split(" ").size).joinToString(" ").trim()
            }
            val candidate = same.ifEmpty { lines.getOrNull(i + 1)?.trim().orEmpty() }
            val cleaned = candidate.trim(' ', ':', '.', '-')
            if (cleaned.length in 2..60 && cleaned.count { it.isLetter() } >= cleaned.length / 2 && cleaned.none { it.isDigit() }) return cleaned
        }
        return null
    }

    private fun groupByFour(s: String) = s.chunked(4).joinToString(" ")

    private fun findIban(text: String): String? {
        for (m in IBAN.findAll(text.uppercase())) {
            var candidate = m.value.replace(" ", "")
            // L'expression peut déborder sur le mot suivant : on raccourcit jusqu'à ce que la clé soit juste.
            while (candidate.length >= 15) {
                if (FormatChecks.iban(candidate) == null) return groupByFour(candidate)
                candidate = candidate.dropLast(1)
            }
        }
        return null
    }

    fun read(text: String, today: LocalDate): Extraction {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return Extraction()
        val whole = flat(lines.joinToString(" "))
        val kind = KINDS.firstOrNull { (" $whole ").contains(" ${it.keyword} ") }
        val mrz = Mrz.find(lines, today.year)

        val found = dates(lines)
        fun labelled(words: List<String>) = found.firstOrNull { f -> words.any { f.context.contains(it) } }?.date
        var expires = labelled(EXPIRY_WORDS)
        val birth = labelled(BIRTH_WORDS)
        val issued = found.firstOrNull { f -> ISSUED_WORDS.any { f.context.contains(it) } && f.date != birth && f.date != expires }?.date
        val obtained = labelled(OBTAINED_WORDS)
        val category = kind?.category ?: if (mrz != null) Category.IDENTITY else null
        if (expires == null && (category == Category.IDENTITY || category == null)) {
            // Sans libellé reconnu : la date future la plus lointaine est vraisemblablement l'expiration.
            expires = found.map { it.date }.filter { it.isAfter(today) }.maxOrNull()
        }

        val iban = findIban(lines.joinToString("\n"))
        val bic = BIC.find(lines.joinToString("\n").uppercase())?.groupValues?.get(1)
        val number = lines.firstNotNullOfOrNull { line ->
            NUMBER.find(line)?.groupValues?.get(1)?.let { raw ->
                // On écarte les mots sans chiffre accrochés à la fin (début du libellé suivant).
                raw.split(' ').dropLastWhile { t -> t.none { it.isDigit() } }.joinToString(" ")
            }?.takeIf { v -> v.count { it.isDigit() } >= 3 }
        }
        val surname = valueAfterLabel(lines, listOf("nom", "noms", "nom de famille", "surname", "name"))
        val given = valueAfterLabel(lines, listOf("prenom", "prenoms", "prenom s", "given names", "given name"))
        val holder = listOfNotNull(given, surname).joinToString(" ").ifEmpty { null }
        val institution = lines.firstOrNull { l -> flat(l).let { f -> listOf("universite", "institut", "ecole", "lycee", "college", "faculte").any { f.contains(it) } } }
        val bank = lines.firstOrNull { l -> flat(l).let { f -> (f.contains("banque") || f.contains("bank")) && !f.contains("identite bancaire") && f.length <= 60 } }

        val base = Extraction(
            category = category ?: if (iban != null) Category.BANK else null,
            docType = kind?.label,
            holder = holder,
            number = number,
            issuedOn = issued,
            expiresOn = expires,
            birthDate = birth,
            obtainedOn = obtained ?: if (kind?.category == Category.DIPLOMA) found.map { it.date }.filter { !it.isAfter(today) && it != birth }.maxOrNull() else null,
            institution = institution,
            bank = bank,
            iban = iban,
            bic = bic,
        )
        if (mrz == null) return base
        // Une zone lisible par machine aux clés justes prime sur tout le reste.
        val mrzType = when {
            mrz.docCode.startsWith("P") -> "Passeport"
            kind?.category == Category.IDENTITY -> kind.label
            else -> "Carte d'identité"
        }
        return base.copy(
            category = Category.IDENTITY,
            docType = mrzType,
            holder = listOf(mrz.givenNames, mrz.surname).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { base.holder },
            number = mrz.number.ifEmpty { base.number },
            issuer = COUNTRIES[mrz.issuer] ?: mrz.issuer.ifEmpty { null },
            expiresOn = mrz.expiry ?: base.expiresOn,
            birthDate = mrz.birth ?: base.birthDate,
            issuedOn = base.issuedOn?.takeIf { it != mrz.expiry && it != mrz.birth },
            fromMrz = true,
        )
    }
}

/** Ligne de texte reconnue, avec sa position dans l'image. */
class TextLine(val text: String, val left: Int, val top: Int, val bottom: Int)

/**
 * Remet les lignes reconnues dans l'ordre de lecture. Les moteurs d'OCR rendent souvent
 * un libellé et sa valeur dans deux blocs distincts : on regroupe ce qui est à la même
 * hauteur sur une seule ligne, de gauche à droite.
 */
object RowAssembler {
    fun assemble(lines: List<TextLine>): String {
        if (lines.isEmpty()) return ""
        val heights = lines.map { (it.bottom - it.top).coerceAtLeast(1) }.sorted()
        val tolerance = heights[heights.size / 2] * 0.6
        val sorted = lines.sortedBy { (it.top + it.bottom) / 2.0 }
        val rows = ArrayList<MutableList<TextLine>>()
        var rowCenter = 0.0
        for (line in sorted) {
            val center = (line.top + line.bottom) / 2.0
            if (rows.isNotEmpty() && center - rowCenter <= tolerance) {
                rows.last() += line
            } else {
                rows += mutableListOf(line)
                rowCenter = center
            }
        }
        return rows.joinToString("\n") { row -> row.sortedBy { it.left }.joinToString("  ") { it.text } }
    }
}
