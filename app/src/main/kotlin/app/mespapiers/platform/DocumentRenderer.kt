package app.mespapiers.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream

/** Décodage d'image borné : jamais plus de [MAX_SIDE] pixels de côté en mémoire. */
object ImageRenderer {
    const val MAX_SIDE = 2560

    /** Renvoie null si le fichier n'est pas une image lisible. */
    fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_SIDE || bounds.outHeight / sample > MAX_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val degrees = try {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }
        if (degrees == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }
}

/**
 * Rendu PDF page par page.
 *
 * Android 11 et suivants : le PDF déchiffré est servi depuis la mémoire (memfd), sans
 * fichier. Avant : PdfRenderer exige un vrai fichier ; une copie en clair est écrite dans
 * [plainDir] (privé, hors sauvegarde) et supprimée par [close], au verrouillage et au
 * démarrage (voir MENACES.md).
 *
 * Le constructeur lève SecurityException pour un PDF protégé par mot de passe et
 * IOException pour un PDF illisible.
 */
class PdfSession(bytes: ByteArray, plainDir: File) : Closeable {
    private val tempFile: File?
    private val descriptor: ParcelFileDescriptor
    private val renderer: PdfRenderer

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tempFile = null
            val fd = Os.memfd_create("mespapiers-pdf", 0)
            descriptor = ParcelFileDescriptor.dup(fd)
            FileOutputStream(fd).use { it.write(bytes) }
            Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
        } else {
            plainDir.mkdirs()
            tempFile = File.createTempFile("pdf", ".tmp", plainDir)
            tempFile.writeBytes(bytes)
            descriptor = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        renderer = try {
            PdfRenderer(descriptor)
        } catch (e: Throwable) {
            descriptor.close()
            tempFile?.delete()
            throw e
        }
    }

    val pageCount: Int get() = renderer.pageCount

    fun render(index: Int, targetWidth: Int = 1240): Bitmap {
        renderer.openPage(index).use { page ->
            val width = targetWidth
            val height = (width.toLong() * page.height / page.width).toInt().coerceIn(1, 4 * width)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }

    override fun close() {
        try {
            renderer.close()
        } catch (e: Exception) {
            // Déjà fermé.
        }
        try {
            descriptor.close()
        } catch (e: Exception) {
            // Déjà fermé.
        }
        tempFile?.delete()
    }
}
