package app.mespapiers.platform

import android.graphics.Bitmap
import android.graphics.Matrix
import app.mespapiers.core.docs.Box
import app.mespapiers.core.docs.DocumentCropper
import app.mespapiers.core.docs.RowAssembler
import app.mespapiers.core.docs.TextLine
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Reconnaissance de texte SUR L'APPAREIL (ML Kit, modèle latin embarqué dans l'APK).
 * L'application n'a pas la permission INTERNET : l'image et le texte ne peuvent pas
 * quitter le téléphone, et la télémétrie de la bibliothèque est sans effet.
 * Voir DECISIONS.md, D-27.
 */
object TextReader {
    /** Lignes reconnues avec leur position, et inclinaison dominante du texte en degrés. */
    class Recognized(val lines: List<TextLine>, val angle: Float) {
        val characters: Int get() = lines.sumOf { l -> l.text.count { it.isLetterOrDigit() } }
    }

    /** À appeler hors du fil principal. */
    fun recognize(bitmap: Bitmap): Recognized {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 45, TimeUnit.SECONDS)
            val raw = result.textBlocks.flatMap { it.lines }
            val lines = raw.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                TextLine(line.text, box.left, box.top, box.bottom, box.right)
            }
            // Inclinaison médiane, pondérée par la longueur des lignes : un mot isolé de travers ne compte pas.
            val angles = raw.flatMap { line -> List(line.text.length.coerceIn(1, 40)) { line.angle } }.sorted()
            return Recognized(lines, if (angles.isEmpty()) 0f else angles[angles.size / 2])
        } finally {
            recognizer.close()
        }
    }

    /** Texte reconnu, remis dans l'ordre de lecture. Chaîne vide si rien n'est lisible. */
    fun read(bitmap: Bitmap): String = RowAssembler.assemble(recognize(bitmap).lines)
}

/**
 * Préparation d'un scan : l'image est redressée (texte à l'endroit) puis recadrée sur le
 * document pour retirer l'arrière-plan. Le recadrage est droit, sans correction de
 * perspective.
 */
object ScanProcessor {
    class Result(val bitmap: Bitmap, val lines: List<TextLine>, val rotated: Boolean, val cropped: Boolean) {
        val changed: Boolean get() = rotated || cropped
    }

    private const val ANALYSIS_SIDE = 320

    private fun rotate(bitmap: Bitmap, degrees: Float): Bitmap =
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)

    private fun quarterTurns(angle: Float): Int = (angle / 90f).roundToInt().mod(4)

    fun process(page: Bitmap): Result {
        var bitmap = page
        var text = TextReader.recognize(bitmap)
        var rotated = false

        // Presque rien de lisible : la photo est peut-être couchée ou à l'envers. On essaie les autres sens.
        if (text.characters < 12) {
            for (degrees in listOf(90f, 270f, 180f)) {
                val candidate = rotate(page, degrees)
                val read = TextReader.recognize(candidate)
                if (read.characters > maxOf(12, text.characters * 2)) {
                    bitmap = candidate
                    text = read
                    rotated = true
                }
            }
        }
        // Texte lu mais incliné d'un quart de tour ou plus : on remet l'image à l'endroit.
        // Deux passes au plus : la seconde rattrape une image restée tête en bas.
        repeat(2) {
            val turns = quarterTurns(text.angle)
            if (turns != 0 && text.lines.isNotEmpty()) {
                bitmap = rotate(bitmap, -90f * turns)
                text = TextReader.recognize(bitmap)
                rotated = true
            }
        }

        val crop = findCrop(bitmap, text.lines) ?: return Result(bitmap, text.lines, rotated, false)
        val cropped = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width, crop.height)
        val shifted = text.lines.map { TextLine(it.text, it.left - crop.left, it.top - crop.top, it.bottom - crop.top, it.right - crop.left) }
        return Result(cropped, shifted, rotated, true)
    }

    /** Rectangle du document dans [bitmap], ou null s'il n'y a rien à rogner. */
    fun findCrop(bitmap: Bitmap, lines: List<TextLine>): Box? {
        val scale = ANALYSIS_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        val luma = IntArray(w * h) { i ->
            val p = pixels[i]
            (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        fun s(v: Int) = (v * scale).toInt()
        val text = lines.map { Box(s(it.left), s(it.top), s(it.right).coerceAtLeast(s(it.left) + 1), s(it.bottom).coerceAtLeast(s(it.top) + 1)) }
        val found = DocumentCropper.find(luma, w, h, text) ?: return null
        fun up(v: Int, max: Int) = (v / scale).toInt().coerceIn(0, max)
        val box = Box(up(found.left, bitmap.width), up(found.top, bitmap.height), up(found.right, bitmap.width), up(found.bottom, bitmap.height))
        return box.takeIf { it.width > 16 && it.height > 16 }
    }
}

/** Aperçu réduit d'une pièce : JPEG de 720 pixels de côté au plus. */
object Thumbnails {
    const val MAX_SIDE = 720

    fun jpeg(source: Bitmap, maxSide: Int = MAX_SIDE, quality: Int = 82): ByteArray {
        val scale = maxSide.toFloat() / maxOf(source.width, source.height)
        val scaled = if (scale >= 1f) source else Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    }
}
