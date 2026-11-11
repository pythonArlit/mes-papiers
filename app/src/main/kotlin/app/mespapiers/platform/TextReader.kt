package app.mespapiers.platform

import android.graphics.Bitmap
import app.mespapiers.core.docs.RowAssembler
import app.mespapiers.core.docs.TextLine
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Reconnaissance de texte SUR L'APPAREIL (ML Kit, modèle latin embarqué dans l'APK).
 * L'application n'a pas la permission INTERNET : l'image et le texte ne peuvent pas
 * quitter le téléphone, et la télémétrie de la bibliothèque est sans effet.
 * Voir DECISIONS.md, D-27.
 */
object TextReader {
    /** Texte reconnu, remis dans l'ordre de lecture. Chaîne vide si rien n'est lisible. À appeler hors du fil principal. */
    fun read(bitmap: Bitmap): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 45, TimeUnit.SECONDS)
            val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                TextLine(line.text, box.left, box.top, box.bottom)
            }
            return RowAssembler.assemble(lines)
        } finally {
            recognizer.close()
        }
    }
}

/** Aperçu réduit d'une pièce : JPEG de 720 pixels de côté au plus. */
object Thumbnails {
    const val MAX_SIDE = 720

    fun jpeg(source: Bitmap): ByteArray {
        val scale = MAX_SIDE.toFloat() / maxOf(source.width, source.height)
        val scaled = if (scale >= 1f) source else Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
    }
}
