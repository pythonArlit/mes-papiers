package app.mespapiers

import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import app.mespapiers.core.access.FileGateStore
import app.mespapiers.core.access.SessionGate
import app.mespapiers.core.crypto.KdfParams
import app.mespapiers.core.crypto.PassphraseKdf
import app.mespapiers.core.docs.LockConfig
import app.mespapiers.core.docs.LockTimers
import app.mespapiers.platform.AndroidClock
import app.mespapiers.platform.KeystoreDeviceKey
import androidx.room.Room
import app.mespapiers.data.Vault
import app.mespapiers.data.VaultDb
import app.mespapiers.data.VaultState
import java.io.File

/** Objets partagés par toute l'application. Rien de sensible n'y survit au verrouillage. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val clock = AndroidClock(context)
    val seedKey = KeystoreDeviceKey(KeystoreDeviceKey.ALIAS_SEED)
    val dataKey = KeystoreDeviceKey(KeystoreDeviceKey.ALIAS_DATA)

    // noBackupFilesDir : répertoire privé que le système n'inclut dans aucune sauvegarde.
    private val store = FileGateStore(File(context.noBackupFilesDir, "gate"))

    @Volatile
    private var kdfTemplate: KdfParams? = null

    val gate = SessionGate(store, seedKey, dataKey, clock, newKdfParams = ::freshKdf)
    val timers = LockTimers(LockConfig())

    private val db = Room.databaseBuilder(appContext, VaultDb::class.java, "vault.db").build()
    val vault = Vault(db, gate)
    val vaultState = VaultState()

    /** Verrouille la session et vide tout ce qui a été déchiffré en mémoire. */
    fun lockNow() {
        gate.lock()
        vaultState.clear()
    }

    private val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    /** Vrai si le téléphone a un code, un schéma ou un mot de passe de verrouillage. */
    val isDeviceSecure: Boolean get() = keyguard.isDeviceSecure

    /** Paramètres calibrés pour cet appareil, avec un sel neuf à chaque appel. */
    private fun freshKdf(): KdfParams {
        val t = kdfTemplate ?: PassphraseKdf.argon2id()
        return KdfParams(t.algo, t.version, PassphraseKdf.newSalt(), t.memoryKiB, t.iterations, t.parallelism)
    }

    /**
     * Mesure la dérivation sur cet appareil (quelques secondes, hors du fil principal).
     * Repli si la mémoire manque : Argon2id au plancher, puis PBKDF2 (DECISIONS.md, D-04).
     */
    fun calibrateKdf() {
        if (kdfTemplate != null) return
        kdfTemplate = try {
            PassphraseKdf.calibrate(PassphraseKdf.DEFAULT_MEMORY_KIB)
        } catch (e: OutOfMemoryError) {
            try {
                PassphraseKdf.calibrate(PassphraseKdf.FLOOR_MEMORY_KIB)
            } catch (e2: OutOfMemoryError) {
                PassphraseKdf.pbkdf2()
            }
        }
    }

    /** Crée les deux clés Keystore. À n'appeler que pour un coffre qui n'existe pas encore. */
    fun createDeviceKeys() {
        check(!gate.isConfigured) { "Le coffre existe déjà" }
        seedKey.create()
        dataKey.create()
    }
}

class MesPapiersApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Extinction de l'écran : verrouillage immédiat, étape intermédiaire comprise.
        val screenOff = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                container.lockNow()
            }
        }
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
