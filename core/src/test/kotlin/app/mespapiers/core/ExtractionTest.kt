package app.mespapiers.core

import app.mespapiers.core.docs.Box
import app.mespapiers.core.docs.DocumentCropper
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

    // ------------------------------------------------------------ carte bilingue sur deux colonnes

    /**
     * Mise en page d'une carte d'identité réelle signalée par un utilisateur : libellés
     * bilingues, valeur sous le libellé, deux colonnes. Les données sont fictives.
     */
    private fun bilingualCard(): List<TextLine> {
        fun line(text: String, left: Int, row: Int) = TextLine(text, left, 100 + row * 50, 136 + row * 50, left + text.length * 16)
        return listOf(
            line("REPUBLIQUE DU NIGER", 300, -2),
            line("CARTE NATIONALE D'IDENTITE AES", 220, -1),
            line("AES NATIONAL IDENTITY CARD", 260, 0),
            line("NIU **********", 420, 1),
            line("Numéro de la carte", 420, 2), line("N° extrait de Naiss", 900, 2),
            line("000198101000755", 420, 3), line("604/JPD/30/01/1987", 900, 3),
            line("Nom/Surname", 420, 4),
            line("EXEMPLE DUPONT", 420, 5),
            line("Prénoms/ GivenNames", 420, 6),
            line("CAMILLE", 420, 7),
            line("Date de naissance/ Date of Birth", 420, 8),
            line("01/01/1981", 420, 9),
            line("Lieu de naissance/ Place of Birth", 420, 10), line("Sexe", 1000, 10), line("Taille", 1200, 10),
            line("VILLE-EXEMPLE/C.U. FICTIVE", 420, 11), line("M", 1000, 11), line("173", 1200, 11),
            line("Téléphone", 420, 12),
            line("90000000", 420, 13),
            line("Profession", 420, 14), line("G.S.", 1000, 14),
            line("GESTIONNAIRE", 420, 15), line("AB+", 1000, 15),
        )
    }

    @Test
    fun carte_bilingue_la_valeur_est_sous_le_libelle() {
        val e = DocumentReader.read(bilingualCard(), today)
        assertEquals(Category.IDENTITY, e.category)
        assertEquals("Carte nationale d'identité", e.docType)
        assertEquals("le prénom n'est pas le mot « GivenNames »", "CAMILLE EXEMPLE DUPONT", e.holder)
        assertEquals("zéros initiaux conservés", "000198101000755", e.number)
        assertEquals(LocalDate.of(1981, 1, 1), e.birthDate)
        assertEquals("Niger", e.issuer)
        assertNull("la date incluse dans le numéro d'extrait n'est pas une date du document", e.issuedOn)
        assertNull("aucune date d'expiration sur ce recto", e.expiresOn)
        val values = e.valuesFor(Category.IDENTITY)
        assertEquals("CAMILLE EXEMPLE DUPONT", values["holder"])
        assertEquals("000198101000755", values["number"])
        assertFalse(values.containsKey("expires_on"))
    }

    @Test
    fun carte_bilingue_lue_dans_le_desordre() {
        val e = DocumentReader.read(bilingualCard().shuffled(java.util.Random(7)), today)
        assertEquals("CAMILLE EXEMPLE DUPONT", e.holder)
        assertEquals("000198101000755", e.number)
    }

    @Test
    fun valeur_a_droite_du_libelle_sur_la_meme_ligne() {
        val lines = listOf(
            TextLine("PASSEPORT", 40, 20, 60, 400),
            TextLine("Nom", 40, 100, 132, 110), TextLine("DUPONT", 300, 101, 133, 460),
            TextLine("Prénoms", 40, 160, 192, 180), TextLine("Camille", 300, 161, 193, 450),
            TextLine("Date d'expiration", 40, 220, 252, 330), TextLine("11/01/2032", 400, 221, 253, 600),
        )
        val e = DocumentReader.read(lines, today)
        assertEquals("Camille DUPONT", e.holder)
        assertEquals(LocalDate.of(2032, 1, 11), e.expiresOn)
    }

    // ------------------------------------------------------------ recadrage automatique

    /** Image de [w] × [h] : fond uni, document rectangulaire avec une « photo » sombre et du « texte ». */
    private fun scene(w: Int, h: Int, doc: Box, background: Int, paper: Int): IntArray {
        val luma = IntArray(w * h) { background }
        for (y in doc.top until doc.bottom) for (x in doc.left until doc.right) luma[y * w + x] = paper
        // Photo d'identité sombre et lignes de texte : des « trous » dans le document.
        for (y in doc.top + 10 until doc.top + 40) for (x in doc.left + 8 until doc.left + 30) luma[y * w + x] = 30
        for (row in 0 until 4) for (x in doc.left + 40 until doc.right - 10 step 2) luma[(doc.top + 15 + row * 12) * w + x] = 20
        return luma
    }

    @Test
    fun recadrage_document_clair_sur_fond_sombre() {
        val doc = Box(60, 40, 260, 170)
        val crop = DocumentCropper.find(scene(320, 240, doc, background = 25, paper = 215), 320, 240)!!
        for ((got, want) in listOf(crop.left to doc.left, crop.top to doc.top, crop.right to doc.right, crop.bottom to doc.bottom)) {
            assertTrue("recadrage $crop pour $doc", kotlin.math.abs(got - want) <= 4)
        }
    }

    @Test
    fun recadrage_document_sombre_sur_fond_clair() {
        val doc = Box(30, 50, 200, 200)
        val crop = DocumentCropper.find(scene(320, 240, doc, background = 235, paper = 90), 320, 240)!!
        assertTrue("$crop", kotlin.math.abs(crop.left - doc.left) <= 4 && kotlin.math.abs(crop.right - doc.right) <= 4)
    }

    @Test
    fun recadrage_fond_de_meme_teinte_repli_sur_le_texte() {
        // Feuille blanche sur table blanche : aucune zone ne se détache, on cadre le texte avec une marge.
        val luma = IntArray(320 * 240) { 230 }
        val text = listOf(Box(100, 80, 220, 90), Box(100, 100, 200, 110), Box(100, 120, 230, 130))
        val crop = DocumentCropper.find(luma, 320, 240, text)!!
        assertTrue("$crop", crop.left < 100 && crop.right > 230 && crop.top < 80 && crop.bottom > 130)
        assertTrue("$crop", crop.left >= 80 && crop.right <= 250 && crop.area < 320L * 240 / 2)
    }

    @Test
    fun recadrage_refuse_une_zone_qui_ne_contient_pas_le_texte() {
        // Une tache claire dans un coin n'est pas le document si le texte est ailleurs.
        val luma = scene(320, 240, Box(5, 5, 120, 110), background = 25, paper = 215)
        val text = listOf(Box(180, 150, 300, 160), Box(180, 170, 290, 180), Box(180, 190, 300, 200))
        val crop = DocumentCropper.find(luma, 320, 240, text)!!
        assertTrue("le texte reste dans le cadre : $crop", crop.left <= 180 && crop.right >= 300 && crop.top <= 150 && crop.bottom >= 200)
    }

    @Test
    fun rien_a_rogner() {
        // Document plein cadre, ou image unie sans texte : on garde l'image entière.
        assertNull(DocumentCropper.find(scene(320, 240, Box(2, 2, 318, 238), 25, 215), 320, 240))
        assertNull(DocumentCropper.find(IntArray(320 * 240) { 128 }, 320, 240))
        assertNull(DocumentCropper.find(IntArray(320 * 240) { 128 }, 320, 240, listOf(Box(10, 10, 300, 20))))
    }
}
