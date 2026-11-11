package app.mespapiers.core

import app.mespapiers.core.access.Backoff
import app.mespapiers.core.access.BackoffState
import app.mespapiers.core.access.ChangeResult
import app.mespapiers.core.access.PassphraseResult
import app.mespapiers.core.access.SessionGate
import app.mespapiers.core.access.Totp
import app.mespapiers.core.access.TotpCheck
import app.mespapiers.core.access.TotpRejection
import app.mespapiers.core.access.TotpResult
import app.mespapiers.core.access.TotpVerifier
import app.mespapiers.core.access.VaultLockedException
import app.mespapiers.core.util.Base32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AccessTest {

    // ------------------------------------------------------------ TOTP (RFC 6238)

    private val rfcSeed = "12345678901234567890".toByteArray(Charsets.US_ASCII)

    /** Annexe B de la RFC 6238, colonne SHA1 (codes à huit chiffres). */
    private val rfcVectors = listOf(
        59L to "94287082",
        1111111109L to "07081804",
        1111111111L to "14050471",
        1234567890L to "89005924",
        2000000000L to "69279037",
        20000000000L to "65353130",
    )

    @Test
    fun totp_vecteurs_rfc6238_huit_chiffres() {
        for ((time, expected) in rfcVectors) {
            assertEquals("t=$time", expected, Totp.code(rfcSeed, Totp.stepAt(time), 8))
        }
    }

    @Test
    fun totp_troncature_a_six_chiffres() {
        for ((time, expected) in rfcVectors) {
            assertEquals("t=$time", expected.takeLast(6), Totp.code(rfcSeed, Totp.stepAt(time)))
        }
        // Zéro initial conservé.
        assertEquals("081804", Totp.code(rfcSeed, Totp.stepAt(1111111109L)))
        assertEquals(6, Totp.code(rfcSeed, 0).length)
    }

    @Test
    fun totp_pas_de_trente_secondes() {
        assertEquals(1L, Totp.stepAt(59))
        assertEquals(2L, Totp.stepAt(60))
        assertEquals(0x23523ECL, Totp.stepAt(1111111109L))
    }

    @Test
    fun graine_de_160_bits_et_lien_otpauth() {
        val seed = Totp.newSeed()
        assertEquals(20, seed.size)
        assertFalse(seed.contentEquals(Totp.newSeed()))
        val uri = Totp.otpAuthUri(seed)
        assertTrue(uri.startsWith("otpauth://totp/Mes%20Papiers:"))
        assertTrue(uri.contains("secret=${Base32.encode(seed)}"))
        assertTrue(uri.contains("issuer=Mes%20Papiers"))
        assertTrue(uri.contains("algorithm=SHA1") && uri.contains("digits=6") && uri.contains("period=30"))
        assertEquals(Base32.encode(seed), Totp.manualKey(seed).replace(" ", ""))
    }

    @Test
    fun verification_tolerance_expiration_et_rejeu() {
        val v = TotpVerifier()
        val now = 1_700_000_000L
        val step = Totp.stepAt(now)
        fun code(s: Long) = Totp.code(rfcSeed, s)
        val none = Long.MIN_VALUE

        assertEquals(TotpCheck.Valid(step), v.check(rfcSeed, code(step), now, none))
        assertEquals(TotpCheck.Valid(step - 1), v.check(rfcSeed, code(step - 1), now, none))
        assertEquals(TotpCheck.Valid(step + 1), v.check(rfcSeed, code(step + 1), now, none))
        assertEquals(TotpCheck.Valid(step), v.check(rfcSeed, " ${code(step).take(3)} ${code(step).drop(3)} ", now, none))

        // Hors tolérance : reconnu comme périmé ou décalé, pas accepté.
        assertEquals(TotpCheck.Expired, v.check(rfcSeed, code(step - 2), now, none))
        assertEquals(TotpCheck.Expired, v.check(rfcSeed, code(step + 5), now, none))

        // Rejeu : pas inférieur ou égal au dernier pas accepté.
        assertEquals(TotpCheck.AlreadyUsed, v.check(rfcSeed, code(step), now, step))
        assertEquals(TotpCheck.AlreadyUsed, v.check(rfcSeed, code(step - 1), now, step))
        assertEquals(TotpCheck.Valid(step + 1), v.check(rfcSeed, code(step + 1), now, step))

        val wrong = (0..999_999).map { "%06d".format(it) }.first { c -> (step - 20..step + 20).none { code(it) == c } }
        assertEquals(TotpCheck.Wrong, v.check(rfcSeed, wrong, now, none))
        assertEquals(TotpCheck.Wrong, v.check(rfcSeed, "12345", now, none))
        assertEquals(TotpCheck.Wrong, v.check(rfcSeed, "12a456", now, none))
        assertEquals(TotpCheck.Wrong, v.check(rfcSeed, "", now, none))
    }

    // ------------------------------------------------------------ délai progressif

    @Test
    fun delai_progressif_bareme() {
        val b = Backoff()
        assertEquals(listOf(0L, 0L, 0L, 0L), (1..4).map { b.delayFor(it) })
        assertEquals(
            listOf(30_000L, 60_000L, 300_000L, 900_000L, 3_600_000L, 3_600_000L, 3_600_000L),
            (5..11).map { b.delayFor(it) },
        )
    }

    @Test
    fun delai_insensible_au_changement_d_horloge() {
        val b = Backoff()
        val clock = FakeClock()
        var s = BackoffState()
        repeat(5) { s = b.onFailure(s, clock) }
        assertEquals(30_000L, b.remainingMs(s, clock))
        clock.wall += 86_400_000L * 365 // l'utilisateur avance la date d'un an
        assertEquals(30_000L, b.remainingMs(b.normalize(s, clock), clock))
        clock.wall -= 86_400_000L * 900
        assertEquals(30_000L, b.remainingMs(b.normalize(s, clock), clock))
        clock.elapsed += 29_999
        assertEquals(1L, b.remainingMs(s, clock))
        clock.elapsed += 1
        assertEquals(0L, b.remainingMs(b.normalize(s, clock), clock))
    }

    @Test
    fun delai_survit_au_redemarrage_et_repart_de_zero() {
        val b = Backoff()
        val clock = FakeClock()
        var s = BackoffState()
        repeat(7) { s = b.onFailure(s, clock) } // 5 min
        clock.advance(200_000)
        assertEquals(100_000L, b.remainingMs(s, clock))
        clock.reboot()
        // Avant normalisation comme après, aucun raccourci.
        assertEquals(300_000L, b.remainingMs(s, clock))
        s = b.normalize(s, clock)
        assertEquals(300_000L, b.remainingMs(s, clock))
        assertEquals(7, s.failures)
        clock.advance(300_000)
        s = b.normalize(s, clock)
        assertEquals(0L, b.remainingMs(s, clock))
        // Le compteur d'échecs, lui, n'est remis à zéro que par une ouverture réussie.
        assertEquals(7, s.failures)
    }

    // ------------------------------------------------------------ double validation

    private class Rig {
        val clock = FakeClock()
        val store = MemStore()
        val seedKey = SoftDeviceKey()
        val dataKey = SoftDeviceKey()
        var gate = newGate()
        lateinit var seed: ByteArray
        val pass = "ma phrase très secrète"

        fun newGate() = SessionGate(store, seedKey, dataKey, clock, newKdfParams = { fastKdf() })

        /** Simule l'arrêt du processus : tout l'état en mémoire est perdu. */
        fun restartApp() {
            gate = newGate()
        }

        fun setup() {
            val e = gate.beginSetup(pass.toCharArray())
            seed = Base32.decode(e.manualKey())
            assertNull(gate.confirmSetup(e, codeNow()))
            gate.lock()
            clock.advance(60_000)
        }

        fun codeNow(offsetSteps: Long = 0) = Totp.code(seed, Totp.stepAt(clock.wallMillis() / 1000) + offsetSteps)

        fun wrongCode(): String {
            val now = Totp.stepAt(clock.wallMillis() / 1000)
            val real = (now - 20..now + 20).map { Totp.code(seed, it) }.toSet()
            return (0..999_999).map { "%06d".format(it) }.first { it !in real }
        }

        fun dataKeyHex() = gate.withDataKey { it.toHex() }
    }

    @Test
    fun configuration_rien_n_est_persiste_avant_le_premier_code() {
        val r = Rig()
        val e = r.gate.beginSetup(r.pass.toCharArray())
        assertNull(r.store.record)
        assertFalse(r.gate.isConfigured)
        r.seed = Base32.decode(e.manualKey())
        assertEquals(TotpRejection.WRONG, r.gate.confirmSetup(e, r.wrongCode()))
        assertEquals(TotpRejection.EXPIRED, r.gate.confirmSetup(e, r.codeNow(-4)))
        assertNull(r.store.record)
        assertFalse(r.gate.isOpen)
        assertNull(r.gate.confirmSetup(e, r.codeNow()))
        assertNotNull(r.store.record)
        assertTrue(r.gate.isOpen)
    }

    @Test
    fun phrase_trop_courte_refusee() {
        val r = Rig()
        try {
            r.gate.beginSetup("onze caract".toCharArray())
            fail("11 caractères acceptés")
        } catch (e: IllegalArgumentException) {
        }
        r.gate.beginSetup("douze caract".toCharArray()).discard()
    }

    @Test
    fun phrase_correcte_seule_ne_charge_pas_la_cle_de_donnees() {
        val r = Rig()
        r.setup()
        val before = r.dataKey.unwraps
        assertEquals(PassphraseResult.Accepted, r.gate.submitPassphrase(r.pass.toCharArray()))
        assertFalse(r.gate.isOpen)
        assertTrue(r.gate.isAwaitingTotp)
        // La clé Keystore des données n'a pas été sollicitée du tout.
        assertEquals(before, r.dataKey.unwraps)
        try {
            r.gate.withDataKey { }
            fail("clé de données accessible avant le TOTP")
        } catch (e: VaultLockedException) {
        }
        assertEquals(TotpResult.Opened, r.gate.submitTotp(r.codeNow()))
        assertTrue(r.gate.isOpen)
        assertEquals(before + 1, r.dataKey.unwraps)
        assertEquals(0, r.store.backoff.failures)
    }

    @Test
    fun totp_sans_etape_1_est_refuse() {
        val r = Rig()
        r.setup()
        assertEquals(TotpResult.NoPendingStep, r.gate.submitTotp(r.codeNow()))
        assertFalse(r.gate.isOpen)
    }

    @Test
    fun totp_errone_expire_ou_deja_utilise_est_refuse() {
        val r = Rig()
        r.setup()
        assertEquals(PassphraseResult.Accepted, r.gate.submitPassphrase(r.pass.toCharArray()))
        assertEquals(TotpResult.Rejected(TotpRejection.WRONG, 4), r.gate.submitTotp(r.wrongCode()))
        assertEquals(TotpResult.Rejected(TotpRejection.EXPIRED, 3), r.gate.submitTotp(r.codeNow(-3)))
        val used = r.codeNow()
        assertEquals(TotpResult.Opened, r.gate.submitTotp(used))
        r.gate.lock()
        // Rejeu immédiat du même code dans la même fenêtre de 30 s.
        assertEquals(PassphraseResult.Accepted, r.gate.submitPassphrase(r.pass.toCharArray()))
        assertEquals(TotpResult.Rejected(TotpRejection.ALREADY_USED, 4), r.gate.submitTotp(used))
        assertFalse(r.gate.isOpen)
        // Le code du pas précédent est lui aussi refusé (pas inférieur au dernier accepté).
        assertEquals(TotpResult.Rejected(TotpRejection.ALREADY_USED, 3), r.gate.submitTotp(r.codeNow(-1)))
        r.clock.advance(30_000)
        assertEquals(TotpResult.Opened, r.gate.submitTotp(r.codeNow()))
    }

    @Test
    fun anti_rejeu_survit_au_redemarrage_de_l_application() {
        val r = Rig()
        r.setup()
        r.gate.submitPassphrase(r.pass.toCharArray())
        val used = r.codeNow()
        assertEquals(TotpResult.Opened, r.gate.submitTotp(used))
        r.restartApp()
        assertFalse(r.gate.isOpen)
        assertEquals(PassphraseResult.Accepted, r.gate.submitPassphrase(r.pass.toCharArray()))
        assertEquals(TotpResult.Rejected(TotpRejection.ALREADY_USED, 4), r.gate.submitTotp(used))
    }

    @Test
    fun anti_rejeu_resiste_au_recul_de_l_horloge() {
        val r = Rig()
        r.setup()
        r.gate.submitPassphrase(r.pass.toCharArray())
        val used = r.codeNow()
        assertEquals(TotpResult.Opened, r.gate.submitTotp(used))
        r.gate.lock()
        // L'attaquant recule l'heure pour rejouer un ancien code capturé.
        r.clock.wall -= 3_600_000
        assertEquals(PassphraseResult.Accepted, r.gate.submitPassphrase(r.pass.toCharArray()))
        assertEquals(TotpResult.Rejected(TotpRejection.ALREADY_USED, 4), r.gate.submitTotp(r.codeNow()))
        assertFalse(r.gate.isOpen)
    }

    @Test
    fun cinq_totp_errones_ramenent_a_l_etape_1_et_comptent_comme_un_echec() {
        val r = Rig()
        r.setup()
        assertEquals(PassphraseResult.Accepted, r.gate.submitPassphrase(r.pass.toCharArray()))
        repeat(4) { assertTrue(r.gate.submitTotp(r.wrongCode()) is TotpResult.Rejected) }
        assertEquals(TotpResult.BackToPassphrase(0), r.gate.submitTotp(r.wrongCode()))
        assertFalse(r.gate.isAwaitingTotp)
        assertEquals(1, r.store.backoff.failures)
        // Même le bon code ne passe plus sans refaire l'étape 1.
        assertEquals(TotpResult.NoPendingStep, r.gate.submitTotp(r.codeNow()))
    }

    @Test
    fun etape_intermediaire_expire_apres_trois_minutes() {
        val r = Rig()
        r.setup()
        r.gate.submitPassphrase(r.pass.toCharArray())
        assertEquals(180_000L, r.gate.totpRemainingMs())
        r.clock.advance(179_999)
        assertTrue(r.gate.isAwaitingTotp)
        r.clock.advance(1)
        assertEquals(TotpResult.BackToPassphrase(0), r.gate.submitTotp(r.codeNow()))
        assertFalse(r.gate.isOpen)
        assertEquals(1, r.store.backoff.failures)
    }

    @Test
    fun le_delai_de_l_etape_2_ne_depend_pas_de_l_heure_murale() {
        val r = Rig()
        r.setup()
        r.gate.submitPassphrase(r.pass.toCharArray())
        r.clock.elapsed += 181_000 // trois minutes réelles écoulées…
        r.clock.wall -= 600_000 // …même si l'heure affichée a été reculée
        assertTrue(r.gate.submitTotp(r.codeNow()) is TotpResult.BackToPassphrase)
    }

    @Test
    fun delai_progressif_apres_cinq_phrases_erronees_persistant() {
        val r = Rig()
        r.setup()
        val bad = "mauvaise phrase 123".toCharArray()
        repeat(4) { assertEquals(PassphraseResult.Wrong(0), r.gate.submitPassphrase(bad)) }
        assertEquals(PassphraseResult.Wrong(30_000), r.gate.submitPassphrase(bad))
        // Pendant le délai, même la bonne phrase est refusée sans être examinée.
        val unwraps = r.seedKey.unwraps
        assertEquals(PassphraseResult.Throttled(30_000), r.gate.submitPassphrase(r.pass.toCharArray()))
        assertEquals(unwraps, r.seedKey.unwraps)

        // Arrêt de l'application + changement d'heure : le délai tient.
        r.restartApp()
        r.clock.wall += 86_400_000
        assertEquals(PassphraseResult.Throttled(30_000), r.gate.submitPassphrase(r.pass.toCharArray()))

        // Redémarrage du téléphone : le délai repart entier.
        r.clock.advance(20_000)
        r.clock.reboot()
        r.restartApp()
        assertEquals(PassphraseResult.Throttled(30_000), r.gate.submitPassphrase(r.pass.toCharArray()))
        r.clock.advance(30_000)
        assertEquals(PassphraseResult.Wrong(60_000), r.gate.submitPassphrase(bad))
        r.clock.advance(60_000)
        assertEquals(PassphraseResult.Accepted, r.gate.submitPassphrase(r.pass.toCharArray()))
        assertEquals(TotpResult.Opened, r.gate.submitTotp(r.codeNow()))
        assertEquals(0, r.store.backoff.failures)
        assertEquals(0L, r.gate.throttleRemainingMs())
    }

    @Test
    fun tuer_l_application_pendant_l_etape_2_ne_fait_pas_disparaitre_la_tentative() {
        val r = Rig()
        r.setup()
        repeat(5) {
            assertEquals(PassphraseResult.Accepted, r.gate.submitPassphrase(r.pass.toCharArray()))
            r.gate.submitTotp(r.wrongCode())
            r.restartApp()
        }
        assertEquals(5, r.store.backoff.failures)
        assertTrue(r.gate.submitPassphrase(r.pass.toCharArray()) is PassphraseResult.Throttled)
    }

    @Test
    fun verrouillage_et_relance_redemandent_les_deux_etapes() {
        val r = Rig()
        r.setup()
        r.gate.submitPassphrase(r.pass.toCharArray())
        r.gate.submitTotp(r.codeNow())
        val key = r.dataKeyHex()
        r.gate.lock()
        assertFalse(r.gate.isOpen)
        try {
            r.gate.withDataKey { }
            fail()
        } catch (e: VaultLockedException) {
        }
        r.clock.advance(30_000)
        assertEquals(PassphraseResult.Accepted, r.gate.submitPassphrase(r.pass.toCharArray()))
        assertFalse(r.gate.isOpen)
        assertEquals(TotpResult.Opened, r.gate.submitTotp(r.codeNow()))
        assertEquals("la même clé de données est retrouvée", key, r.dataKeyHex())
        r.restartApp()
        assertFalse(r.gate.isOpen)
    }

    @Test
    fun la_cle_remise_par_withDataKey_est_une_copie_effacee_ensuite() {
        val r = Rig()
        r.setup()
        r.gate.submitPassphrase(r.pass.toCharArray())
        r.gate.submitTotp(r.codeNow())
        var leaked: ByteArray? = null
        val key = r.gate.withDataKey { leaked = it; it.toHex() }
        assertTrue(leaked!!.all { it == 0.toByte() })
        assertEquals(key, r.dataKeyHex())
    }

    @Test
    fun cle_keystore_perdue_coffre_illisible() {
        val r = Rig()
        r.setup()
        r.seedKey.lost = true
        assertEquals(PassphraseResult.VaultUnreadable, r.gate.submitPassphrase(r.pass.toCharArray()))
        assertEquals("ce n'est pas un échec de phrase", 0, r.store.backoff.failures)
        r.seedKey.lost = false
        r.dataKey.lost = true
        assertEquals(PassphraseResult.Accepted, r.gate.submitPassphrase(r.pass.toCharArray()))
        assertEquals(TotpResult.VaultUnreadable, r.gate.submitTotp(r.codeNow()))
        assertFalse(r.gate.isOpen)
    }

    @Test
    fun fichiers_extraits_sans_le_telephone_ne_suffisent_pas() {
        val r = Rig()
        r.setup()
        // Même stockage, même phrase, mais d'autres clés d'appareil (autre téléphone).
        val other = SessionGate(r.store, SoftDeviceKey(), SoftDeviceKey(), r.clock, newKdfParams = { fastKdf() })
        assertEquals(PassphraseResult.VaultUnreadable, other.submitPassphrase(r.pass.toCharArray()))
        assertFalse(other.isOpen)
    }

    @Test
    fun changement_de_phrase() {
        val r = Rig()
        r.setup()
        val next = "nouvelle phrase d'accès"
        assertEquals(ChangeResult.NOT_OPEN, r.gate.changePassphrase(r.pass.toCharArray(), next.toCharArray()))
        r.gate.submitPassphrase(r.pass.toCharArray())
        r.gate.submitTotp(r.codeNow())
        val key = r.dataKeyHex()
        assertEquals(ChangeResult.TOO_SHORT, r.gate.changePassphrase(r.pass.toCharArray(), "court".toCharArray()))
        assertEquals(ChangeResult.WRONG_CURRENT, r.gate.changePassphrase("pas la bonne phrase".toCharArray(), next.toCharArray()))
        assertEquals(ChangeResult.DONE, r.gate.changePassphrase(r.pass.toCharArray(), next.toCharArray()))
        r.gate.lock()
        r.clock.advance(30_000)
        assertTrue(r.gate.submitPassphrase(r.pass.toCharArray()) is PassphraseResult.Wrong)
        assertEquals(PassphraseResult.Accepted, r.gate.submitPassphrase(next.toCharArray()))
        assertEquals(TotpResult.Opened, r.gate.submitTotp(r.codeNow()))
        assertEquals(key, r.dataKeyHex())
    }

    @Test
    fun nouvel_enrolement_totp_detruit_l_ancienne_graine() {
        val r = Rig()
        r.setup()
        assertEquals(ChangeResult.NOT_OPEN, r.gate.beginTotpReenroll(r.pass.toCharArray()).first)
        r.gate.submitPassphrase(r.pass.toCharArray())
        r.gate.submitTotp(r.codeNow())
        assertEquals(ChangeResult.WRONG_CURRENT, r.gate.beginTotpReenroll("pas la bonne phrase".toCharArray()).first)

        val (result, enrollment) = r.gate.beginTotpReenroll(r.pass.toCharArray())
        assertEquals(ChangeResult.DONE, result)
        val oldSeed = r.seed
        val newSeed = Base32.decode(enrollment!!.manualKey())
        assertFalse(oldSeed.contentEquals(newSeed))
        val savesBefore = r.store.saves
        // Un code de l'ancienne graine ne confirme pas la nouvelle ; rien n'est remplacé.
        assertNotNull(r.gate.confirmTotpReenroll(enrollment, r.codeNow()))
        assertEquals(savesBefore, r.store.saves)
        r.seed = newSeed
        assertNull(r.gate.confirmTotpReenroll(enrollment, r.codeNow()))

        r.gate.lock()
        r.clock.advance(60_000)
        r.gate.submitPassphrase(r.pass.toCharArray())
        val oldCode = Totp.code(oldSeed, Totp.stepAt(r.clock.wallMillis() / 1000))
        if (oldCode != r.codeNow()) assertTrue(r.gate.submitTotp(oldCode) is TotpResult.Rejected)
        assertEquals(TotpResult.Opened, r.gate.submitTotp(r.codeNow()))
    }

    @Test
    fun sans_phrase_ou_sans_totp_aucun_acces() {
        val r = Rig()
        r.setup()
        // Sans la phrase : on n'atteint jamais l'étape 2.
        assertTrue(r.gate.submitPassphrase("je ne la connais pas".toCharArray()) is PassphraseResult.Wrong)
        assertEquals(TotpResult.NoPendingStep, r.gate.submitTotp(r.codeNow()))
        // Avec la phrase mais sans l'application d'authentification : cinq essais puis retour.
        r.gate.submitPassphrase(r.pass.toCharArray())
        repeat(5) { r.gate.submitTotp(r.wrongCode()) }
        assertFalse(r.gate.isOpen)
        assertFalse(r.gate.isAwaitingTotp)
    }
}
