package app.mespapiers

import android.content.Context

/** Production : la seconde étape est toujours saisie par l'utilisateur. Aucun raccourci. */
object SecondFactor {
    const val AUTOMATIC = false

    @Suppress("UNUSED_PARAMETER")
    fun remember(context: Context, manualKey: String) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun codes(context: Context): List<String> = emptyList()
}
