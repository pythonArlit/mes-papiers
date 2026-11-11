package app.mespapiers

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.mespapiers.ui.AppRoot

/**
 * Activité unique. Quelle que soit la façon dont elle est lancée, elle affiche l'écran
 * que l'état d'accès impose : connexion tant que la double validation n'est pas faite.
 */
class MainActivity : ComponentActivity() {
    private val vm: AccessViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        // Aucun état sensible n'est restauré : on ignore l'état sauvegardé.
        super.onCreate(null)
        if (Variant.BLOCK_SCREENSHOTS) {
            // Bloque les captures et masque le contenu dans les applications récentes.
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        // Ignore les touches reçues quand une autre fenêtre recouvre l'application.
        window.decorView.filterTouchesWhenObscured = true
        setContent { AppRoot(vm) }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        vm.onBackground()
        super.onStop()
    }
}
