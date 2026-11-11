package app.mespapiers.core

import app.mespapiers.core.docs.DocumentReader
import app.mespapiers.core.docs.Extraction
import app.mespapiers.core.docs.Mrz
import app.mespapiers.core.docs.RowAssembler
import app.mespapiers.core.docs.TextLine
import app.mespapiers.core.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ExtractionTest {
    private val today = LocalDate.of(2026, 11, 11)

    // Spécimens ICAO 9303 (pays fictif « Utopia »).
    private val td3 = listOf(
        "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<",
        "L898902C36UTO7408122F1204159ZE184226B<<<<<10",
    )
    private val td1 = listOf(
        "I<UTOD231458907<<<<<<<<<<<<<<<",
        "7408122F1204159UTO<<<<<<<<<<<6",
        "ERIKSSON<<ANNA<MARIA<<<<<<<<<<",
    )

    @Test
    fun cle_de_controle_icao() {
        assertEquals(6, Mrz.checkDigit("L898902C3"))
        assertEquals(2, Mrz.checkDigit("740812"))
        assertEquals(9, Mrz.checkDigit("120415"))
        assertEquals(7, Mrz.checkDigit("D23145890"))
    }

    @Test
    fun passeport_zone_lisible_par_machine() {
        val text = (listOf("PASSEPORT / PASSPORT", "UTOPIA", "Nom / Surname", "ERIKSSON") + td3).joinToString("\n")
        val e = DocumentReader.read(text, today)
        assertTrue(e.fromMrz)
        assertEquals(Category.IDENTITY, e.category)
        assertEquals("Passeport", e.docType)
        assertEquals("L898902C3", e.number)
        assertEquals("ANNA MARIA ERIKSSON", e.holder)
        assertEquals("UTO", e.issuer)
        assertEquals(LocalDate.of(2012, 4, 15), e.expiresOn)
        assertEquals(LocalDate.of(1974, 8, 12), e.birthDate)
        val values = e.valuesFor(Category.IDENTITY)
        assertEquals("L898902C3", values["number"])
        assertEquals("2012-04-15", values["expires_on"])
        assertEquals("Passeport", values["type"])
    }

    @Test
    fun carte_d_identite_trois_lignes() {
        val e = DocumentReader.read((listOf("CARTE NATIONALE D'IDENTITÉ") + td1).joinToString("\n"), today)
        assertTrue(e.fromMrz)
        assertEquals("Carte nationale d'identité", e.docType)
        assertEquals("D23145890", e.number)
        assertEquals("ANNA MARIA ERIKSSON", e.holder)
        assertEquals(LocalDate.of(2012, 4, 15), e.expiresOn)
    }

    @Test
    fun zone_lue_avec_les_erreurs_habituelles_de_l_ocr() {
        // Espaces parasites, chevrons finaux perdus, O à la place de 0 dans une date.
        val noisy = listOf("P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<", "L898902C36UTO74O8122F12O4159ZE184226B<<<<<1 0")
        val e = DocumentReader.read(noisy.joinToString("\n"), today)
        assertTrue(e.fromMrz)
        assertEquals("L898902C3", e.number)
        assertEquals(LocalDate.of(2012, 4, 15), e.expiresOn)
    }

    @Test
    fun zone_aux_cles_fausses_ignoree() {
        val wrong = listOf(td3[0], "L898902C46UTO7408123F1204158ZE184226B<<<<<10")
        val e = DocumentReader.read(wrong.joinToString("\n"), today)
        assertFalse("une zone dont les clés sont fausses ne doit rien remplir", e.fromMrz)
        assertNull(e.holder)
    }

    @Test
    fun carte_sans_zone_lisible_dates_et_numero_par_libelles() {
        val text = """
            RÉPUBLIQUE DU NIGER
            CARTE NATIONALE D'IDENTITÉ
            N° 0012345678
            Nom : MAHAMADOU
            Prénoms : Aïcha Fatouma
            Née le 05/03/1991 à Zinder
            Délivrée le 12/01/2022
            Expire le 11/01/2032
        """.trimIndent()
        val e = DocumentReader.read(text, today)
        assertEquals(Category.IDENTITY, e.category)
        assertEquals("Carte nationale d'identité", e.docType)
        assertEquals("zéros initiaux conservés", "0012345678", e.number)
        assertEquals("Aïcha Fatouma MAHAMADOU", e.holder)
        assertEquals(LocalDate.of(1991, 3, 5), e.birthDate)
        assertEquals(LocalDate.of(2022, 1, 12), e.issuedOn)
        assertEquals(LocalDate.of(2032, 1, 11), e.expiresOn)
    }

    @Test
    fun libelle_sur_la_ligne_precedente_et_date_en_toutes_lettres() {
        val text = "PERMIS DE CONDUIRE\nDate d'expiration\n3 février 2031\nDate de délivrance\n1er mars 2021"
        val e = DocumentReader.read(text, today)
        assertEquals("Permis de conduire", e.docType)
        assertEquals(LocalDate.of(2031, 2, 3), e.expiresOn)
        assertEquals(LocalDate.of(2021, 3, 1), e.issuedOn)
    }

    @Test
    fun sans_libelle_la_date_future_est_proposee_comme_expiration() {
        val e = DocumentReader.read("PASSEPORT\n14.06.2019\n13.06.2029\n02.02.1988", today)
        assertEquals(LocalDate.of(2029, 6, 13), e.expiresOn)
    }

    @Test
    fun diplome() {
        val text = """
            UNIVERSITÉ ABDOU MOUMOUNI DE NIAMEY
            Faculté des Sciences et Techniques
            DIPLÔME DE MASTER
            Session de juillet 2019
            N° 2019/FST/00457
            Fait à Niamey, le 20/09/2019
        """.trimIndent()
        val e = DocumentReader.read(text, today)
        assertEquals(Category.DIPLOMA, e.category)
        assertEquals("Master", e.docType)
        assertEquals("UNIVERSITÉ ABDOU MOUMOUNI DE NIAMEY", e.institution)
        assertEquals("2019/FST/00457", e.number)
        val values = e.valuesFor(Category.DIPLOMA)
        assertEquals("2019-09-20", values["obtained_on"])
        assertEquals("2019/FST/00457", values["reference"])
    }

    @Test
    fun rib_avec_iban_et_bic() {
        val text = """
            RELEVÉ D'IDENTITÉ BANCAIRE
            Banque Atlantique Niger
            Titulaire du compte
            IBAN : FR76 3000 6000 0112 3456 7890 189 BIC : AGRIFRPP
        """.trimIndent()
        val e = DocumentReader.read(text, today)
        assertEquals(Category.BANK, e.category)
        assertEquals("FR76 3000 6000 0112 3456 7890 189", e.iban)
        assertEquals("AGRIFRPP", e.bic)
        assertEquals("Banque Atlantique Niger", e.bank)
        assertEquals("FR76 3000 6000 0112 3456 7890 189", e.valuesFor(Category.BANK)["iban"])
    }

    @Test
    fun iban_a_cle_fausse_non_propose() {
        assertNull(DocumentReader.read("IBAN FR76 3000 6000 0112 3456 7890 188", today).iban)
    }

    @Test
    fun acte_de_naissance_et_justificatif() {
        assertEquals(Category.CIVIL_STATUS, DocumentReader.read("EXTRAIT D'ACTE DE NAISSANCE\nN° 1234 du 02/01/1990", today).category)
        val bill = DocumentReader.read("NIGELEC\nFACTURE D'ÉLECTRICITÉ\nNombre de kWh 250\nDate limite 30/11/2026", today)
        assertEquals(Category.ADDRESS, bill.category)
        assertNull("« Nombre » n'est pas un numéro de document", bill.number)
    }

    @Test
    fun texte_vide_ou_sans_rapport_rien_n_est_invente() {
        assertTrue(DocumentReader.read("", today).isEmpty)
        val e = DocumentReader.read("Bonjour à tous\nRendez-vous demain à la personne le plus tôt possible", today)
        assertEquals(Extraction(), e)
        assertTrue(e.valuesFor(Category.OTHER).isEmpty())
    }

    @Test
    fun valeurs_proposees_seulement_pour_les_champs_du_gabarit() {
        val e = Extraction(docType = "Passeport", number = "X1", iban = "FR76", expiresOn = LocalDate.of(2030, 1, 1))
        assertEquals(setOf("type", "number", "expires_on"), e.valuesFor(Category.IDENTITY).keys)
        assertEquals(setOf("iban"), e.valuesFor(Category.BANK).keys)
        assertEquals(setOf("reference", "valid_until"), e.valuesFor(Category.OTHER).keys)
    }

    @Test
    fun lignes_remises_dans_l_ordre_de_lecture() {
        // Libellés à gauche et valeurs à droite, rendus dans le désordre par l'OCR.
        val lines = listOf(
            TextLine("11/01/2032", 400, 204, 236),
            TextLine("CARTE D'IDENTITÉ", 40, 20, 60),
            TextLine("Expire le", 40, 200, 232),
            TextLine("MAHAMADOU", 400, 102, 134),
            TextLine("Nom :", 40, 100, 132),
        )
        val text = RowAssembler.assemble(lines)
        assertEquals("CARTE D'IDENTITÉ\nNom :  MAHAMADOU\nExpire le  11/01/2032", text)
        val e = DocumentReader.read(text, today)
        assertEquals("MAHAMADOU", e.holder)
        assertEquals(LocalDate.of(2032, 1, 11), e.expiresOn)
        assertEquals("", RowAssembler.assemble(emptyList()))
    }
}
