package app.mespapiers.platform

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import app.mespapiers.core.util.Clock

/**
 * Horloge réelle. Les délais de sécurité reposent sur elapsedRealtime (monotone, sommeil
 * compris) et sur le compteur de démarrages, pas sur l'heure réglable par l'utilisateur.
 */
class AndroidClock(context: Context) : Clock {
    private val resolver = context.applicationContext.contentResolver

    override fun wallMillis(): Long = System.currentTimeMillis()

    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()

    override fun bootCount(): Int = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, 0)
}
