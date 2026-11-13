package app.mespapiers

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiObjectNotFoundException
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.mespapiers.core.model.AttachmentRole
import app.mespapiers.core.model.Category
import app.mespapiers.core.model.Field
import app.mespapiers.core.model.FieldType
import app.mespapiers.core.model.Ids
import app.mespapiers.core.model.Record
import app.mespapiers.platform.Thumbnails
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/**
 * Visite guidée de la variante demo, pour produire les captures d'écran : elle pilote la
 * vraie interface et n'utilise que des données fictives. Elle ne s'exécute que sur demande
 * (argument d'instrumentation « tour=1 »), à part des autres tests.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTour {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val passphrase = "le chameau bleu traverse Agadez"
    private var index = 0

    private fun settle(ms: Long = 900) {
        device.waitForIdle()
        SystemClock.sleep(ms)
    }

    private fun shot(name: String) {
        settle()
        index++
        device.executeShellCommand("screencap -p /sdcard/Download/mespapiers/%02d-%s.png".format(index, name))
    }

    private fun scrollDown() {
        val w = device.displayWidth
        val h = device.displayHeight
        device.swipe(w / 2, (h * 0.72).toInt(), w / 2, (h * 0.30).toInt(), 25)
        settle(500)
    }

    private fun visibleTexts(): String = try {
        device.findObjects(By.pkg(context.packageName)).filter { it.text != null || it.contentDescription != null }.take(60)
            .joinToString(" | ") { "${it.text ?: ""}/${it.contentDescription ?: ""} ${it.visibleBounds.toShortString()}" }
    } catch (e: StaleObjectException) {
        "(écran en cours de mise à jour)"
    }

    private fun waitFor(selector: BySelector, timeout: Long = 20_000) {
        if (!device.wait(Until.hasObject(selector), timeout)) throw AssertionError("jamais affiché : $selector ; à l'écran : " + visibleTexts())
    }

    /** Appuie sur l'élément, en faisant défiler l'écran s'il n'est pas visible. */
    private fun tap(selector: BySelector, timeout: Long = 20_000) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            // L'écran se redessine (aperçus qui arrivent) : un élément trouvé peut ne plus exister l'instant d'après.
            try {
                val o = device.findObject(selector)
                if (o == null) {
                    SystemClock.sleep(300)
                    continue
                }
                val b = o.visibleBounds
                if (b.height() > 20 && b.centerY() in 120 until device.displayHeight - 120) {
                    o.click()
                    settle()
                    return
                }
                scrollDown()
            } catch (e: StaleObjectException) {
                // Élément connu mais hors de l'écran (ou écran en cours de mise à jour) : on fait défiler.
                scrollDown()
            }
        }
        throw AssertionError("introuvable : $selector ; à l'écran : " + visibleTexts())
    }

    /**
     * Variante par l'ancienne interface d'UiAutomator, qui réinterroge l'écran à chaque appel :
     * elle sert pour les éléments placés sous une liste dont les aperçus se chargent encore.
     */
    private fun tapTextContaining(part: String, timeout: Long = 40_000) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            try {
                val o = device.findObject(UiSelector().textContains(part))
                if (o.exists()) {
                    val b = o.visibleBounds
                    if (b.height() > 20 && b.centerY() in 120 until device.displayHeight - 120) {
                        o.click()
                        settle()
                        return
                    }
                }
            } catch (e: UiObjectNotFoundException) {
                // Disparu entre deux lectures : on réessaie.
            }
            scrollDown()
        }
        throw AssertionError("introuvable : texte contenant « $part »")
    }

    private fun type(vararg values: String) {
        waitFor(By.clazz("android.widget.EditText"))
        repeat(3) {
            try {
                val fields = device.findObjects(By.clazz("android.widget.EditText"))
                values.forEachIndexed { i, v -> fields[i].text = v }
                settle(400)
                return
            } catch (e: StaleObjectException) {
                SystemClock.sleep(400)
            }
        }
        settle(400)
    }

    private fun specimen(title: String, lines: List<Pair<String, String>>, portrait: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(1600, 1010, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.drawColor(Color.rgb(232, 243, 236))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.rgb(15, 76, 92)
        c.drawRect(0f, 0f, 1600f, 150f, p)
        p.color = Color.WHITE; p.textSize = 62f; p.isFakeBoldText = true
        c.drawText(title, 60f, 98f, p)
        p.isFakeBoldText = false
        if (portrait) {
            p.color = Color.rgb(176, 190, 197)
            c.drawRoundRect(RectF(70f, 220f, 430f, 680f), 18f, 18f, p)
            p.color = Color.rgb(120, 134, 142)
            c.drawCircle(250f, 380f, 85f, p)
            c.drawRoundRect(RectF(120f, 500f, 380f, 680f), 90f, 90f, p)
        }
        val x = if (portrait) 500f else 90f
        var y = 250f
        for ((label, value) in lines) {
            p.color = Color.rgb(90, 104, 112); p.textSize = 32f
            c.drawText(label, x, y, p)
            p.color = Color.rgb(20, 32, 31); p.textSize = 50f
            c.drawText(value, x, y + 58f, p)
            y += 132f
        }
        p.color = Color.argb(60, 179, 38, 30); p.textSize = 150f; p.isFakeBoldText = true
        c.save(); c.rotate(-18f, 800f, 560f); c.drawText("SPÉCIMEN", 420f, 620f, p); c.restore()
        return bitmap
    }

    private fun seed() {
        val container = (context.applicationContext as MesPapiersApp).container
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        fun text(k: String, v: String) = Field(k, FieldType.TEXT, v)
        fun id(k: String, v: String) = Field(k, FieldType.IDENTIFIER, v, sensitive = true)
        fun date(k: String, d: LocalDate) = Field(k, FieldType.DATE, d.toString())
        val soon = today.plusDays(21)
        val card = Record(
            Ids.new(), Category.IDENTITY, "Carte nationale d'identité", listOf("spécimen", "identité"), true,
            listOf(text("type", "Carte nationale d'identité"), text("holder", "Camille EXEMPLE"), id("number", "000198101000755"), text("issuer", "Niger"), date("issued_on", LocalDate.of(2022, 1, 12)), date("expires_on", soon)),
            soon, now, now,
        )
        val passport = Record(
            Ids.new(), Category.IDENTITY, "Passeport", listOf("voyage"), true,
            listOf(text("type", "Passeport"), text("holder", "Camille EXEMPLE"), id("number", "00XX12345"), date("expires_on", LocalDate.of(2031, 6, 13))),
            LocalDate.of(2031, 6, 13), now, now,
        )
        val diploma = Record(
            Ids.new(), Category.DIPLOMA, "Diplôme de Master", listOf("études"), false,
            listOf(text("title", "Master en gestion de projets"), text("institution", "Université Fictive de Nulle-Part"), date("obtained_on", LocalDate.of(2019, 9, 20)), Field("reference", FieldType.IDENTIFIER, "2019/FST/00457")),
            null, now, now,
        )
        val rib = Record(
            Ids.new(), Category.BANK, "RIB compte courant", emptyList(), false,
            listOf(text("bank", "Banque Imaginaire"), text("holder", "Camille EXEMPLE"), id("account_number", "000123456789"), id("iban", "FR76 3000 6000 0112 3456 7890 189"), Field("bic", FieldType.IDENTIFIER, "AGRIFRPP")),
            null, now, now,
        )
        val expired = today.minusDays(40)
        val insurance = Record(
            Ids.new(), Category.OTHER, "Assurance habitation", listOf("logement"), false,
            listOf(Field("reference", FieldType.IDENTIFIER, "POL-000-2024"), date("valid_until", expired)),
            expired, now, now,
        )
        listOf(card, passport, diploma, rib, insurance).forEach { container.vault.put(it) }

        fun attach(record: Record, role: AttachmentRole, name: String, bitmap: Bitmap) {
            val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            val meta = container.attachments.import(record.id, role, name, ByteArrayInputStream(png), png.size.toLong(), { false }) {}
            container.attachments.putThumb(meta, Thumbnails.jpeg(bitmap))
        }
        attach(card, AttachmentRole.FRONT, "carte-recto.png", specimen("CARTE NATIONALE D'IDENTITÉ", listOf("Numéro de la carte" to "000198101000755", "Nom / Surname" to "EXEMPLE", "Prénoms / Given names" to "CAMILLE", "Date de naissance" to "01/01/1990"), portrait = true))
        attach(card, AttachmentRole.BACK, "carte-verso.png", specimen("CARTE NATIONALE D'IDENTITÉ", listOf("Délivrée le" to "12/01/2022", "Expire le" to "voir fiche", "Autorité" to "Administration fictive"), portrait = false))
        attach(passport, AttachmentRole.FRONT, "passeport.png", specimen("PASSEPORT", listOf("Numéro" to "00XX12345", "Nom" to "EXEMPLE", "Prénoms" to "CAMILLE", "Expiration" to "13/06/2031"), portrait = true))
        attach(diploma, AttachmentRole.DOCUMENT, "diplome.png", specimen("DIPLÔME DE MASTER", listOf("Établissement" to "Université Fictive de Nulle-Part", "Intitulé" to "Gestion de projets", "Session" to "Septembre 2019", "Référence" to "2019/FST/00457"), portrait = false))
    }

    @Test
    fun visite() {
        assumeTrue("visite guidée non demandée", InstrumentationRegistry.getArguments().getString("tour") == "1")
        Configurator.getInstance().waitForIdleTimeout = 2_500
        device.executeShellCommand("mkdir -p /sdcard/Download/mespapiers")
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch)

        waitFor(By.text("Bienvenue"))
        shot("bienvenue")
        tap(By.text("Créer mon coffre"))
        type(passphrase, passphrase)
        shot("phrase-secrete")
        tap(By.text("Continuer"))
        waitFor(By.text("Scanner un document"), 180_000)
        shot("accueil-vide")

        seed()
        tap(By.desc("Verrouiller"))
        waitFor(By.text("Ouvrir le coffre"))
        type(passphrase)
        shot("connexion")
        tap(By.text("Continuer"))
        waitFor(By.text("Scanner un document"), 120_000)
        waitFor(By.text("Assurance habitation"))
        shot("accueil")
        scrollDown()
        shot("accueil-suite")

        tapTextContaining("Voir et rechercher")
        waitFor(By.text("Tous les documents"))
        shot("liste")
        tap(By.text("Carte nationale d'identité"))
        waitFor(By.text("Informations"))
        shot("fiche")
        scrollDown()
        shot("fiche-suite")
        scrollDown()
        shot("fiche-pieces")
        tap(By.desc("Modifier"))
        waitFor(By.text("Enregistrer"))
        shot("modification")
        device.pressBack()
        settle()
        device.swipe(device.displayWidth / 2, (device.displayHeight * 0.3).toInt(), device.displayWidth / 2, (device.displayHeight * 0.9).toInt(), 10)
        device.swipe(device.displayWidth / 2, (device.displayHeight * 0.3).toInt(), device.displayWidth / 2, (device.displayHeight * 0.9).toInt(), 10)
        tap(By.descStartsWith("Ouvrir"))
        shot("document")
        device.pressBack()
        device.pressBack()
        device.pressBack()
        settle()
        waitFor(By.text("Scanner un document"))
        tap(By.text("Scanner un document"))
        shot("choix-scan")
        tap(By.text("Annuler"))
        tap(By.text("Saisir à la main, sans scanner"))
        waitFor(By.text("Quel type de document ?"))
        shot("categories")
        device.pressBack()
        settle()
        tap(By.desc("Sauvegarde"))
        waitFor(By.text("Créer ma phrase de récupération"))
        shot("sauvegarde")
        tap(By.text("Créer ma phrase de récupération"))
        waitFor(By.text("Je l'ai notée").let { By.textContains("ai notée") })
        shot("phrase-de-recuperation")
    }
}
