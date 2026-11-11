package app.mespapiers

import android.content.Context
import app.mespapiers.core.access.Totp
import app.mespapiers.core.util.Base32
import java.io.File

/**
 * RACCOURCI DE DÉMONSTRATION, propre à cette variante (DECISIONS.md, D-20).
 *
 * La seconde étape n'est pas demandée : la graine TOTP est conservée EN CLAIR dans le
 * stockage privé de l'application de démonstration et le code est calculé à ta place.
 * La démo n'offre donc que la protection de la phrase secrète. Ce fichier n'est pas
 * compilé dans la variante prod, où la double validation reste obligatoire.
 */
object SecondFactor {
    const val AUTOMATIC = true

    private fun file(context: Context) = File(context.noBackupFilesDir, "demo-second-factor")

    fun remember(context: Context, manualKey: String) {
        file(context).writeText(manualKey.replace(" ", ""))
    }

    /** Codes du pas courant et du suivant (le second sert si le premier vient d'être utilisé). */
    fun codes(context: Context): List<String> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val seed = Base32.decode(f.readText())
        val step = Totp.stepAt(System.currentTimeMillis() / 1000)
        return listOf(Totp.code(seed, step), Totp.code(seed, step + 1))
    }
}
