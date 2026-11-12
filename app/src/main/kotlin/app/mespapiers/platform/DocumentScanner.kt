package app.mespapiers.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * Scanner de documents de Google (ML Kit) : appareil photo avec détection des bords,
 * coins ajustables à la main, correction de perspective et filtres.
 *
 * Il est fourni par les services Google Play, qui téléchargent son module à la première
 * utilisation ; l'application elle-même n'a toujours pas la permission INTERNET ni la
 * permission CAMERA. Absent ou indisponible (pas de services Google Play, moins de
 * 1,7 Go de RAM, module non téléchargé), on se rabat sur l'appareil photo du système.
 * Voir DECISIONS.md, D-32.
 */
object DocumentScanner {
    private val options = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(true)
        .setPageLimit(1)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .build()

    fun start(activity: Activity, onReady: (IntentSender) -> Unit, onUnavailable: () -> Unit) {
        try {
            GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
                .addOnSuccessListener { sender -> onReady(sender) }
                .addOnFailureListener { onUnavailable() }
        } catch (e: Exception) {
            onUnavailable()
        }
    }

    /** Image de la page scannée, ou null si le scan a été abandonné. */
    fun resultUri(data: Intent?): Uri? = try {
        GmsDocumentScanningResult.fromActivityResultIntent(data)?.pages?.firstOrNull()?.imageUri
    } catch (e: Exception) {
        null
    }

    fun activityOf(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
