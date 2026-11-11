package app.mespapiers.core

import app.mespapiers.core.docs.DeadlineState
import app.mespapiers.core.docs.Deadlines
import app.mespapiers.core.docs.ExportReminder
import app.mespapiers.core.docs.FileSignature
import app.mespapiers.core.docs.FormatChecks
import app.mespapiers.core.docs.LockConfig
import app.mespapiers.core.docs.LockTimers
import app.mespapiers.core.docs.PassphraseStrength
import app.mespapiers.core.docs.RecoveryPhrase
import app.mespapiers.core.docs.ReminderPlanner
import app.mespapiers.core.docs.ReminderSettings
import app.mespapiers.core.docs.SearchIndex
import app.mespapiers.core.docs.TextNormalizer
import app.mespapiers.core.model.AttachmentMeta
import app.mespapiers.core.model.AttachmentRole
import app.mespapiers.core.model.Category
import app.mespapiers.core.model.Codec
import app.mespapiers.core.model.Field
import app.mespapiers.core.model.FieldType
import app.mespapiers.core.model.FileFormat
import app.mespapiers.core.model.Ids
import app.mespapiers.core.model.Record
import app.mespapiers.core.model.Templates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class DocsTest {

    // ------------------------------------------------------------ fiches

    @Test
    fun un_titre_et_une_categorie_suffisent() {
        val r = Record(Ids.new(), Category.OTHER, "Contrat de bail")
        assertEquals(r, Codec.decodeRecord(Codec.encodeRecord(r)))
    }

    @Test
    fun fiche_aller_retour_avec_zeros_initiaux_et_champs_personnalises() {
        val r = Record(
            id = Ids.new(),
            category = Category.BANK,
            title = "RIB compte courant",
            tags = listOf("banque", "salaire"),
            favorite = true,
            fields = listOf(
                Field("account_number", FieldType.IDENTIFIER, "000123456789", sensitive = true),
                Field("rib", FieldType.IDENTIFIER, "NE058 01001 000123456789 07", sensitive = true),
                Field("c1", FieldType.DATE, "2024-02-29", custom = true, label = "Ouverture"),
                Field("c2", FieldType.TEXT, "Agence du Plateau — Niamey", custom = true, label = "Agence", sensitive = false),
            ),
            expiry = LocalDate.of(2031, 12, 31),
            createdAtMs = 1,
            updatedAtMs = 2,
        )
        val back = Codec.decodeRecord(Codec.encodeRecord(r))
        assertEquals(r, back)
        assertEquals("000123456789", back.fields[0].value)
        assertEquals(LocalDate.of(2024, 2, 29), back.fields[2].dateOrNull())
    }

    @Test
    fun fiche_illisible_ou_identifiant_dangereux_refuses() {
        try {
            Codec.decodeRecord(byteArrayOf(9, 0, 0))
            fail()
        } catch (e: IOException) {
        }
        for (bad in listOf("../../etc/passwd", "", "ABCDEF12-0000-0000-0000-000000000000", "a/b")) {
            assertFalse(Ids.isValid(bad))
            try {
                Record(bad, Category.OTHER, "x")
                fail("identifiant accepté : $bad")
            } catch (e: IllegalArgumentException) {
            }
        }
        assertTrue(Ids.isValid(Ids.new()))
    }

    @Test
    fun piece_jointe_aller_retour() {
        val a = AttachmentMeta(Ids.new(), Ids.new(), AttachmentRole.BACK, FileFormat.JPEG, "../scan verso.JPG", 123_456)
        assertEquals(a, Codec.decodeAttachment(Codec.encodeAttachment(a)))
    }

    @Test
    fun gabarits_sans_secret_de_paiement() {
        val forbidden = listOf("cvv", "cvc", "pin", "password", "mot_de_passe", "code_carte", "cryptogram")
        for (c in Category.entries) {
            val keys = Templates.fieldsFor(c).map { it.key }
            assertEquals("clés uniques pour $c", keys.size, keys.toSet().size)
            assertTrue(keys.none { k -> forbidden.any { k.contains(it) } })
        }
        assertTrue(Templates.fieldsFor(Category.ADDRESS).none { it.key.contains("postal") || it.key.contains("zip") })
        assertNotNull(Templates.fieldsFor(Category.IDENTITY).single { it.isExpiry })
        assertEquals(listOf(AttachmentRole.FRONT, AttachmentRole.BACK), Templates.rolesFor(Category.IDENTITY))
    }

    // ------------------------------------------------------------ recherche

    @Test
    fun recherche_insensible_a_la_casse_et_aux_accents() {
        assertEquals("diplome d'ingenieur", TextNormalizer.normalize("  Diplôme d'Ingénieur "))
        assertEquals("oeuvre", TextNormalizer.normalize("Œuvre"))
        val a = Record(Ids.new(), Category.DIPLOMA, "Diplôme de Maîtrise", tags = listOf("Université", "2019"))
        val b = Record(Ids.new(), Category.IDENTITY, "Passeport", tags = listOf("voyage"))
        val c = Record(Ids.new(), Category.CIVIL_STATUS, "Acte de naissance")
        val index = SearchIndex()
        index.rebuild(listOf(a, b, c)) {
            when (it.category) {
                Category.DIPLOMA -> "Diplômes et attestations"
                Category.IDENTITY -> "Identité"
                else -> "État civil"
            }
        }
        assertEquals(listOf(a.id), index.search("DIPLOME maitrise"))
        assertEquals(listOf(a.id), index.search("universite"))
        assertEquals(listOf(b.id), index.search("identite"))
        assertEquals(listOf(c.id), index.search("etat civil"))
        assertEquals(emptyList<String>(), index.search("permis"))
        assertEquals(3, index.search("  ").size)
        index.clear()
        assertEquals(0, index.search("").size)
    }

    // ------------------------------------------------------------ formats

    @Test
    fun controles_de_format_seulement_des_avertissements() {
        assertNull(FormatChecks.iban("FR76 3000 6000 0112 3456 7890 189"))
        assertNull(FormatChecks.iban("gb82 west 1234 5698 7654 32"))
        assertEquals(FormatChecks.Warning.IBAN_CHECKSUM, FormatChecks.iban("FR76 3000 6000 0112 3456 7890 188"))
        assertEquals(FormatChecks.Warning.IBAN_FORMAT, FormatChecks.iban("12345"))
        assertNull("IBAN facultatif", FormatChecks.iban(""))
        assertNull(FormatChecks.rib("NE058 01001 000123456789 07"))
        assertEquals(FormatChecks.Warning.RIB_UNUSUAL, FormatChecks.rib("12 34"))
        assertNull(FormatChecks.rib(""))
        assertNull(FormatChecks.identifier("000123"))
    }

    @Test
    fun signature_de_fichier_et_non_extension() {
        assertEquals(FileFormat.JPEG, FileSignature.detect(hex("ffd8ffe000104a464946")))
        assertEquals(FileFormat.PNG, FileSignature.detect(hex("89504e470d0a1a0a0000000d")))
        assertEquals(FileFormat.PDF, FileSignature.detect("%PDF-1.7\n".toByteArray()))
        assertNull("exécutable renommé en .pdf", FileSignature.detect(hex("4d5a90000300000004000000")))
        assertNull("archive zip", FileSignature.detect(hex("504b0304")))
        assertNull(FileSignature.detect(ByteArray(0)))
        assertNull(FileSignature.detect(hex("ffd8")))
    }

    @Test
    fun indicateur_de_robustesse() {
        assertEquals(PassphraseStrength.Level.TOO_SHORT, PassphraseStrength.estimate("court"))
        assertEquals(PassphraseStrength.Level.WEAK, PassphraseStrength.estimate("aaaaaaaaaaaaaaaa"))
        assertEquals(PassphraseStrength.Level.WEAK, PassphraseStrength.estimate("1234567890123456"))
        val good = PassphraseStrength.estimate("le chameau bleu traverse Agadez en 1998")
        assertTrue(good == PassphraseStrength.Level.GOOD || good == PassphraseStrength.Level.STRONG)
        // Aucune règle de composition : une longue phrase en minuscules n'est pas « faible ».
        assertTrue(PassphraseStrength.estimate("quatre mots tout simples ici") > PassphraseStrength.Level.WEAK)
    }

    // ------------------------------------------------------------ phrase de récupération

    @Test
    fun phrase_de_recuperation_format_et_entropie() {
        val p = RecoveryPhrase.generate()
        assertEquals(34, p.size)
        assertEquals(7, String(p).split("-").size)
        assertTrue(String(p).split("-").all { it.length == 4 })
        assertTrue("140 bits au moins", RecoveryPhrase.LENGTH * 5 >= 128)
        assertFalse(String(p) == String(RecoveryPhrase.generate()))
        val n = RecoveryPhrase.normalize(p)!!
        assertEquals(28, n.size)
        assertEquals(String(p), String(RecoveryPhrase.format(n)))
        // Répartition : sur 200 phrases, les 32 symboles apparaissent tous.
        val seen = (1..200).flatMap { RecoveryPhrase.normalize(RecoveryPhrase.generate())!!.toList() }.toSet()
        assertEquals(32, seen.size)
    }

    @Test
    fun phrase_de_recuperation_saisie_tolerante() {
        val canonical = "0123456789ABCDEFGHJKMNPQRSTV"
        assertEquals(canonical, String(RecoveryPhrase.normalize("0123-4567-89ab-cdef-ghjk-mnpq-rstv".toCharArray())!!))
        assertEquals(canonical, String(RecoveryPhrase.normalize("o1z3 4567 89AB CDEF GHJK MNPQ RSTV".replace('z', '2').toCharArray())!!))
        assertEquals(canonical, String(RecoveryPhrase.normalize("OL23456789ABCDEFGHJKMNPQRSTV".toCharArray())!!))
        assertNull(RecoveryPhrase.normalize("0123-4567".toCharArray()))
        assertNull(RecoveryPhrase.normalize((canonical + "0").toCharArray()))
        assertNull(RecoveryPhrase.normalize("U123456789ABCDEFGHJKMNPQRSTV".toCharArray()))
    }

    // ------------------------------------------------------------ échéances

    private val today = LocalDate.of(2026, 11, 11)

    @Test
    fun etat_des_echeances() {
        assertEquals(DeadlineState.NONE, Deadlines.status(null, today).state)
        assertEquals(DeadlineState.EXPIRED, Deadlines.status(today.minusDays(1), today).state)
        assertEquals(-400L, Deadlines.status(today.minusDays(400), today).daysLeft)
        assertEquals("valable le jour même", DeadlineState.SOON, Deadlines.status(today, today).state)
        assertEquals(0L, Deadlines.status(today, today).daysLeft)
        assertEquals(DeadlineState.SOON, Deadlines.status(today.plusDays(30), today).state)
        assertEquals(DeadlineState.VALID, Deadlines.status(today.plusDays(31), today).state)
        assertEquals(DeadlineState.VALID, Deadlines.status(LocalDate.of(2036, 2, 29), today).state)
    }

    @Test
    fun aujourd_hui_depend_du_fuseau_pas_la_date_d_expiration() {
        // 11 novembre 23 h 30 à Niamey = 12 novembre à Tokyo.
        val instant = ZonedDateTime.of(2026, 11, 11, 23, 30, 0, 0, ZoneId.of("Africa/Niamey")).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 11, 11), Deadlines.today(instant, ZoneId.of("Africa/Niamey")))
        assertEquals(LocalDate.of(2026, 11, 12), Deadlines.today(instant, ZoneId.of("Asia/Tokyo")))
        val expiry = LocalDate.of(2026, 11, 11)
        assertEquals(DeadlineState.SOON, Deadlines.status(expiry, Deadlines.today(instant, ZoneId.of("Africa/Niamey"))).state)
        assertEquals(DeadlineState.EXPIRED, Deadlines.status(expiry, Deadlines.today(instant, ZoneId.of("Asia/Tokyo"))).state)
    }

    @Test
    fun rappels_j30_j7_j1_a_9h_locales() {
        val niamey = ZoneId.of("Africa/Niamey")
        val now = ZonedDateTime.of(2026, 11, 11, 10, 0, 0, 0, niamey).toInstant().toEpochMilli()
        val expiry = LocalDate.of(2027, 3, 15)
        val plan = ReminderPlanner.plan(expiry, ReminderSettings(), niamey, now)
        assertEquals(listOf(30, 7, 1), plan.map { it.offsetDays })
        val expected = listOf(LocalDate.of(2027, 2, 13), LocalDate.of(2027, 3, 8), LocalDate.of(2027, 3, 14))
            .map { it.atTime(9, 0).atZone(niamey).toInstant().toEpochMilli() }
        assertEquals(expected, plan.map { it.triggerEpochMs })
    }

    @Test
    fun rappels_echeances_proches_passees_ou_absentes() {
        val zone = ZoneId.of("Africa/Niamey")
        val now = ZonedDateTime.of(2026, 11, 11, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val s = ReminderSettings()
        assertEquals(emptyList<Any>(), ReminderPlanner.plan(null, s, zone, now))
        assertEquals(emptyList<Any>(), ReminderPlanner.plan(LocalDate.of(2026, 1, 1), s, zone, now))
        // Expire dans 5 jours : J−30 et J−7 sont passés, seul J−1 reste.
        assertEquals(listOf(1), ReminderPlanner.plan(LocalDate.of(2026, 11, 16), s, zone, now).map { it.offsetDays })
        // Expire demain : J−1 tombait aujourd'hui à 9 h, il est 10 h.
        assertEquals(emptyList<Any>(), ReminderPlanner.plan(LocalDate.of(2026, 11, 12), s, zone, now))
        assertEquals(emptyList<Any>(), ReminderPlanner.plan(LocalDate.of(2027, 1, 1), s.copy(enabled = false), zone, now))
        assertEquals(listOf(14), ReminderPlanner.plan(LocalDate.of(2027, 1, 1), s.copy(offsetsDays = listOf(14, 14)), zone, now).map { it.offsetDays })
    }

    @Test
    fun rappels_recalcules_apres_changement_de_fuseau_et_heure_d_ete() {
        val expiry = LocalDate.of(2027, 4, 1)
        val now = 0L
        val s = ReminderSettings(offsetsDays = listOf(1))
        val niamey = ReminderPlanner.plan(expiry, s, ZoneId.of("Africa/Niamey"), now).single().triggerEpochMs
        val paris = ReminderPlanner.plan(expiry, s, ZoneId.of("Europe/Paris"), now).single().triggerEpochMs
        // Le 31 mars 2027, Paris est en heure d'été (UTC+2), Niamey reste à UTC+1.
        assertEquals(3_600_000L, niamey - paris)
        // Heure inexistante (passage à l'heure d'été, 28 mars 2027 à 2 h 30) : décalée, pas d'erreur.
        val gap = ReminderPlanner.plan(LocalDate.of(2027, 3, 29), ReminderSettings(offsetsDays = listOf(1), hour = 2), ZoneId.of("Europe/Paris"), now)
        assertEquals(1, gap.size)
    }

    @Test
    fun rappel_d_export() {
        val day = 86_400_000L
        assertEquals(ExportReminder.State.NEVER, ExportReminder.state(null, 100 * day))
        assertEquals(ExportReminder.State.RECENT, ExportReminder.state(80 * day, 100 * day))
        assertEquals(ExportReminder.State.STALE, ExportReminder.state(60 * day, 100 * day))
    }

    // ------------------------------------------------------------ verrouillage automatique

    @Test
    fun verrouillage_apres_inactivite_et_arriere_plan() {
        val t = LockTimers(LockConfig())
        t.onUnlocked(0)
        assertFalse(t.shouldLock(119_999))
        assertTrue(t.shouldLock(120_000))
        t.onInteraction(100_000)
        assertFalse(t.shouldLock(200_000))
        assertEquals(220_000L, t.nextCheckAt())

        t.onBackground(200_000)
        assertFalse(t.shouldLock(229_999))
        assertTrue(t.shouldLock(230_000))
        // Retour avant 30 s : la session continue.
        t.onForeground(210_000)
        assertFalse(t.shouldLock(210_001))
        // Retour après 30 s : verrouillée, et le retour au premier plan n'y change rien.
        t.onBackground(300_000)
        t.onForeground(330_000)
        assertTrue(t.shouldLock(330_000))
    }

    @Test
    fun delai_de_grace_borne_pour_selecteur_photo_et_partage() {
        val t = LockTimers(LockConfig())
        t.onUnlocked(0)
        t.startGrace(10_000)
        t.onBackground(10_100)
        assertFalse("au-delà des 30 s d'arrière-plan, la grâce protège l'opération", t.shouldLock(100_000))
        assertTrue("mais elle est bornée à 3 min", t.shouldLock(190_000))
        t.onForeground(100_000)
        assertFalse(t.shouldLock(100_001))
        // La grâce ne se reporte pas sur un passage ultérieur en arrière-plan.
        t.onBackground(110_000)
        assertTrue(t.shouldLock(140_000))
    }

    @Test
    fun reglages_de_verrouillage_bornes() {
        for (bad in listOf({ LockConfig(backgroundMs = 600_000) }, { LockConfig(inactivityMs = 5_000) }, { LockConfig(inactivityMs = 3_600_000) })) {
            try {
                bad()
                fail("réglage hors plage accepté")
            } catch (e: IllegalArgumentException) {
            }
        }
        LockConfig(backgroundMs = 0, inactivityMs = 30_000)
    }
}
