package app.mespapiers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mespapiers.core.docs.DocumentReader
import app.mespapiers.core.model.Category
import android.graphics.Matrix
import app.mespapiers.platform.ScanProcessor
import app.mespapiers.platform.TextReader
import app.mespapiers.platform.Thumbnails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Lecture automatique de bout en bout sur appareil : image → texte reconnu → champs proposés.
 * Les « documents » sont des images de texte net, fabriquées pour le test : ce n'est pas
 * une mesure de qualité sur de vraies photos.
 */
@RunWith(AndroidJUnit4::class)
class TextReaderTest {
    private val today = LocalDate.of(2026, 11, 11)

    private fun page(lines: List<String>, monospace: Boolean = false): Bitmap {
        val bitmap = Bitmap.createBitmap(1400, 120 + lines.size * 90, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = if (monospace) 40f else 52f
            typeface = if (monospace) Typeface.MONOSPACE else Typeface.SANS_SERIF
        }
        lines.forEachIndexed { i, line -> canvas.drawText(line, 60f, 110f + i * 90f, paint) }
        return bitmap
    }

    @Test
    fun carte_d_identite_lue_et_champs_proposes() {
        val text = TextReader.read(
            page(listOf("CARTE NATIONALE D'IDENTITE", "N° 0012345678", "Nom : MAHAMADOU", "Délivrée le 12/01/2022", "Expire le 11/01/2032")),
        )
        assertTrue("texte reconnu : $text", text.contains("0012345678"))
        val e = DocumentReader.read(text, today)
        assertEquals(text, Category.IDENTITY, e.category)
        assertEquals(text, "Carte nationale d'identité", e.docType)
        assertEquals(text, "0012345678", e.number)
        assertEquals(text, LocalDate.of(2032, 1, 11), e.expiresOn)
        assertEquals(text, "2032-01-11", e.valuesFor(Category.IDENTITY)["expires_on"])
    }

    @Test
    fun passeport_zone_lisible_par_machine_lue() {
        val text = TextReader.read(
            page(listOf("PASSEPORT", "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<", "L898902C36UTO7408122F1204159ZE184226B<<<<<10"), monospace = true),
        )
        val e = DocumentReader.read(text, today)
        // La zone n'est acceptée que si ses clés de contrôle sont justes : aucun risque de faux numéro.
        assertTrue("zone non reconnue dans : $text", e.fromMrz)
        assertEquals("L898902C3", e.number)
        assertEquals(LocalDate.of(2012, 4, 15), e.expiresOn)
        assertEquals("Passeport", e.docType)
    }

    @Test
    fun image_sans_texte_rien_n_est_invente() {
        val blank = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val e = DocumentReader.read(TextReader.read(blank), today)
        assertTrue(e.isEmpty)
    }

    @Test
    fun apercu_reduit_borne() {
        val jpeg = Thumbnails.jpeg(page(List(20) { "Ligne $it" }))
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
        assertTrue(maxOf(options.outWidth, options.outHeight) <= Thumbnails.MAX_SIDE)
        assertTrue("aperçu de ${jpeg.size} octets", jpeg.size < 300_000)
    }

    private val idCard = listOf("CARTE NATIONALE D'IDENTITE", "N° 0012345678", "Nom : MAHAMADOU", "Délivrée le 12/01/2022", "Expire le 11/01/2032")

    private fun rotated(source: Bitmap, degrees: Float): Bitmap =
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(degrees) }, true)

    @Test
    fun photo_couchee_ou_a_l_envers_redressee() {
        val upright = page(idCard)
        for (degrees in listOf(90f, 180f, 270f)) {
            val result = ScanProcessor.process(rotated(upright, degrees))
            assertTrue("rotation de $degrees° non détectée", result.rotated)
            assertTrue("image redressée en paysage pour $degrees° : ${result.bitmap.width}x${result.bitmap.height}", result.bitmap.width > result.bitmap.height)
            val e = DocumentReader.read(result.lines, today)
            assertEquals("après $degrees°", "0012345678", e.number)
            assertEquals("après $degrees°", LocalDate.of(2032, 1, 11), e.expiresOn)
        }
        assertTrue("une image déjà droite n'est pas tournée", !ScanProcessor.process(upright).rotated)
    }

    @Test
    fun arriere_plan_rogne_autour_du_document() {
        val card = page(idCard)
        // Le document posé sur un fond sombre bien plus grand que lui.
        val photo = Bitmap.createBitmap(card.width + 900, card.height + 1100, Bitmap.Config.ARGB_8888)
        Canvas(photo).apply {
            drawColor(Color.rgb(28, 28, 32))
            drawBitmap(card, 500f, 600f, null)
        }
        val result = ScanProcessor.process(photo)
        assertTrue(result.cropped)
        val w = result.bitmap.width
        val h = result.bitmap.height
        assertTrue("largeur $w pour ${card.width}", kotlin.math.abs(w - card.width) < card.width * 0.06)
        assertTrue("hauteur $h pour ${card.height}", kotlin.math.abs(h - card.height) < card.height * 0.10)
        // Les coins de l'image rognée sont du papier, plus du fond.
        assertTrue(Color.red(result.bitmap.getPixel(w / 2, h / 2)) > 200 || Color.red(result.bitmap.getPixel(20, h - 20)) > 200)
        val e = DocumentReader.read(result.lines, today)
        assertEquals("0012345678", e.number)
        assertEquals("MAHAMADOU", e.holder)
    }

    @Test
    fun photo_couchee_sur_fond_sombre_redressee_puis_rognee() {
        val card = page(idCard)
        val photo = Bitmap.createBitmap(card.width + 700, card.height + 900, Bitmap.Config.ARGB_8888)
        Canvas(photo).apply {
            drawColor(Color.rgb(28, 28, 32))
            drawBitmap(card, 350f, 450f, null)
        }
        val result = ScanProcessor.process(rotated(photo, 90f))
        assertTrue(result.rotated && result.cropped)
        assertTrue("${result.bitmap.width}x${result.bitmap.height}", result.bitmap.width > result.bitmap.height)
        assertTrue(kotlin.math.abs(result.bitmap.width - card.width) < card.width * 0.08)
        assertEquals("0012345678", DocumentReader.read(result.lines, today).number)
    }

    /** Mise en page d'une carte bilingue sur deux colonnes, valeurs sous les libellés. Données fictives. */
    @Test
    fun carte_bilingue_sur_deux_colonnes() {
        val bitmap = Bitmap.createBitmap(1700, 1000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 34f }
        val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 46f }
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 54f; isFakeBoldText = true }
        canvas.drawText("REPUBLIQUE DU NIGER", 520f, 80f, title)
        canvas.drawText("CARTE NATIONALE D'IDENTITE AES", 420f, 150f, title)
        var y = 250f
        fun pair(l: String, v: String, x: Float, at: Float) {
            canvas.drawText(l, x, at, label)
            canvas.drawText(v, x, at + 56f, value)
        }
        pair("Numéro de la carte", "000198101000755", 480f, y); pair("N° extrait de Naiss", "604/JPD/30/01/1987", 1100f, y)
        y += 150f
        pair("Nom/Surname", "EXEMPLE DUPONT", 480f, y)
        y += 150f
        pair("Prénoms/ GivenNames", "CAMILLE", 480f, y)
        y += 150f
        pair("Date de naissance/ Date of Birth", "01/01/1981", 480f, y)
        y += 150f
        pair("Profession", "GESTIONNAIRE", 480f, y); pair("Sexe", "M", 1200f, y)

        val result = ScanProcessor.process(bitmap)
        val e = DocumentReader.read(result.lines, today)
        val seen = result.lines.joinToString(" | ") { it.text }
        assertEquals(seen, "CAMILLE EXEMPLE DUPONT", e.holder)
        assertEquals(seen, "000198101000755", e.number)
        assertEquals(seen, LocalDate.of(1981, 1, 1), e.birthDate)
        assertEquals(seen, "Niger", e.issuer)
        assertEquals(seen, "Carte nationale d'identité", e.docType)
    }
}
