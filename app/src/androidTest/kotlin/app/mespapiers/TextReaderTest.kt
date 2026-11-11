package app.mespapiers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mespapiers.core.docs.DocumentReader
import app.mespapiers.core.model.Category
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
}
