package app.mespapiers

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mespapiers.core.access.FileGateStore
import app.mespapiers.core.access.SessionGate
import app.mespapiers.core.access.Totp
import app.mespapiers.core.archive.ArchiveError
import app.mespapiers.core.archive.ArchiveException
import app.mespapiers.core.crypto.Aead
import app.mespapiers.core.crypto.PassphraseKdf
import app.mespapiers.core.docs.RecoveryPhrase
import app.mespapiers.core.model.AttachmentRole
import app.mespapiers.core.model.Category
import app.mespapiers.core.model.Field
import app.mespapiers.core.model.FieldType
import app.mespapiers.core.model.Ids
import app.mespapiers.core.model.Record
import app.mespapiers.core.util.Base32
import app.mespapiers.data.AttachmentStore
import app.mespapiers.data.BackupManager
import app.mespapiers.data.ExportCancelled
import app.mespapiers.data.Vault
import app.mespapiers.data.VaultDb
import app.mespapiers.platform.AndroidClock
import app.mespapiers.platform.KeystoreDeviceKey
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate

/** Sauvegarde puis restauration « sur une autre installation », avec le vrai Keystore. */
@RunWith(AndroidJUnit4::class)
class BackupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val gateDir = File(context.noBackupFilesDir, "gate-backuptest")
    private val filesDir = File(context.noBackupFilesDir, "attachments")
    private val dbName = "vault-backup-test.db"
    private val clock = AndroidClock(context)
    private val pass = "ma phrase très secrète"
    private val recovery = RecoveryPhrase.normalize(RecoveryPhrase.generate())!!
    private var generation = 0

    private lateinit var seedKey: KeystoreDeviceKey
    private lateinit var dataKey: KeystoreDeviceKey
    private lateinit var gate: SessionGate
    private lateinit var db: VaultDb
    private lateinit var vault: Vault
    private lateinit var store: AttachmentStore
    private lateinit var backup: BackupManager

    private val passport = Record(
        Ids.new(), Category.IDENTITY, "Passeport de test", listOf("voyage"), true,
        listOf(Field("number", FieldType.IDENTIFIER, "00AB12345", sensitive = true)), LocalDate.of(2031, 5, 1),
    )
    private val rib = Record(Ids.new(), Category.BANK, "RIB de test", fields = listOf(Field("account_number", FieldType.IDENTIFIER, "000123456789", sensitive = true)))

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(80, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private val front = png(Color.rgb(15, 76, 92))
    private val back = png(Color.rgb(95, 211, 166))

    /** Une « installation » : nouvelles clés Keystore, état d'accès vide, base vide. */
    private fun freshInstall() {
        if (::db.isInitialized) db.close()
        if (::seedKey.isInitialized) {
            seedKey.delete()
            dataKey.delete()
        }
        generation++
        gateDir.deleteRecursively()
        filesDir.deleteRecursively()
        context.deleteDatabase(dbName)
        File(context.noBackupFilesDir, "recovery.bin").delete()
        File(context.noBackupFilesDir, "last-export").delete()
        seedKey = KeystoreDeviceKey("mespapiers.backuptest.seed.$generation").also { it.create() }
        dataKey = KeystoreDeviceKey("mespapiers.backuptest.data.$generation").also { it.create() }
        gate = SessionGate(
            FileGateStore(gateDir), seedKey, dataKey, clock,
            newKdfParams = { PassphraseKdf.argon2id(PassphraseKdf.FLOOR_MEMORY_KIB, PassphraseKdf.FLOOR_ITERATIONS) },
        )
        db = VaultDb.open(context, dbName)
        vault = Vault(db, gate)
        store = AttachmentStore(context, db, gate)
        backup = BackupManager(context, db, gate, vault, store, clock)
    }

    private fun setUpVault(restoredKey: ByteArray? = null) {
        val e = gate.beginSetup(pass.toCharArray(), restoredKey)
        assertNull(gate.confirmSetup(e, Totp.code(Base32.decode(e.manualKey()), Totp.stepAt(clock.wallMillis() / 1000))))
    }

    private fun exportArchive(): ByteArray {
        setUpVault()
        vault.put(passport)
        vault.put(rib)
        for ((role, data) in listOf(AttachmentRole.FRONT to front, AttachmentRole.BACK to back)) {
            store.import(passport.id, role, "piece-DUPONT.png", ByteArrayInputStream(data), data.size.toLong(), { false }) {}
        }
        backup.saveRecoveryPhrase(recovery)
        val out = ByteArrayOutputStream()
        val result = backup.export(out, recovery, { false }) {}
        assertEquals(2, result.records)
        assertEquals(2, result.attachments)
        assertEquals(0, result.unreadable)
        return out.toByteArray()
    }

    private fun stagedFiles() = filesDir.listFiles()?.size ?: 0

    @Before
    fun setUp() = freshInstall()

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
        gateDir.deleteRecursively()
        filesDir.deleteRecursively()
        File(context.noBackupFilesDir, "recovery.bin").delete()
        File(context.noBackupFilesDir, "last-export").delete()
        seedKey.delete()
        dataKey.delete()
    }

    @Test
    fun phrase_de_recuperation_verifiee_sans_etre_stockee() {
        setUpVault()
        assertFalse(backup.hasRecoveryPhrase)
        backup.saveRecoveryPhrase(recovery)
        assertTrue(backup.hasRecoveryPhrase)
        assertTrue(backup.checkRecoveryPhrase(recovery))
        assertFalse(backup.checkRecoveryPhrase(RecoveryPhrase.normalize(RecoveryPhrase.generate())!!))
        val stored = String(File(context.noBackupFilesDir, "recovery.bin").readBytes(), Charsets.ISO_8859_1)
        assertFalse("la phrase ne doit pas être sur le disque", stored.contains(String(recovery)))
    }

    @Test
    fun export_puis_restauration_sur_une_autre_installation() {
        val before = clock.wallMillis()
        val archive = exportArchive()
        assertTrue("date du dernier export enregistrée", backup.lastExportMs()!! >= before)
        val text = String(archive, Charsets.ISO_8859_1)
        for (secret in listOf("Passeport", "00AB12345", "000123456789", "DUPONT", pass, String(recovery))) {
            assertFalse("« $secret » lisible dans l'archive", text.contains(secret))
        }

        // Autre téléphone : autres clés Keystore, rien de l'ancienne installation.
        freshInstall()
        val key = Aead.newKey()
        val summary = backup.restore(ByteArrayInputStream(archive), recovery, key)
        assertEquals(2, summary.records)
        assertEquals(2, summary.attachments)
        assertFalse("rien n'est activé avant la configuration de l'accès", gate.isConfigured)
        assertFalse(gate.isOpen)

        // Nouvelle phrase d'accès et nouvel enrôlement : c'est cela qui active les données.
        setUpVault(key)
        val records = vault.loadAll().records.sortedBy { it.title }
        assertEquals(listOf("Passeport de test", "RIB de test"), records.map { it.title })
        assertEquals(passport.copy(id = records[0].id), records[0])
        assertEquals("000123456789", records[1].fields[0].value)
        assertFalse("les identifiants de l'archive ne sont pas réutilisés", records[0].id == passport.id)
        val metas = store.loadAll()
        assertEquals(2, metas.size)
        assertTrue(metas.all { it.recordId == records[0].id })
        assertArrayEquals(front, store.readAll(metas.first { it.role == AttachmentRole.FRONT }))
        assertArrayEquals(back, store.readAll(metas.first { it.role == AttachmentRole.BACK }))
        assertTrue("la même phrase de récupération sert aux prochains exports", backup.checkRecoveryPhrase(recovery))
        assertEquals(summary.exportedAtMs, backup.lastExportMs())
    }

    @Test
    fun mauvaise_phrase_archive_modifiee_ou_tronquee_installation_toujours_vide() {
        val archive = exportArchive()
        val wrong = RecoveryPhrase.normalize(RecoveryPhrase.generate())!!
        val cases = listOf(
            Triple("mauvaise phrase", archive, wrong) to ArchiveError.WRONG_SECRET,
            Triple("tronquée", archive.copyOf(archive.size - 40), recovery) to ArchiveError.CORRUPTED,
            Triple("modifiée", archive.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }, recovery) to ArchiveError.CORRUPTED,
            Triple("version inconnue", archive.copyOf().also { it[7] = 9 }, recovery) to ArchiveError.UNKNOWN_VERSION,
            Triple("pas une archive", "%PDF-1.7 autre chose".toByteArray(), recovery) to ArchiveError.BAD_FORMAT,
        )
        for ((case, expected) in cases) {
            val (name, bytes, phrase) = case
            freshInstall()
            try {
                backup.restore(ByteArrayInputStream(bytes), phrase, Aead.newKey())
                fail("$name : archive acceptée")
            } catch (e: ArchiveException) {
                assertEquals(name, expected, e.error)
            }
            assertEquals("$name : aucune fiche", 0, db.records().count())
            assertEquals("$name : aucune pièce en base", 0, db.attachments().ids().size)
            assertEquals("$name : aucun fichier", 0, stagedFiles())
            assertFalse(name, backup.hasRecoveryPhrase)
            assertNull(name, backup.lastExportMs())
            assertFalse(name, gate.isConfigured)
        }
    }

    @Test
    fun restauration_preparee_mais_jamais_activee_effacee_au_demarrage() {
        val archive = exportArchive()
        freshInstall()
        backup.restore(ByteArrayInputStream(archive), recovery, Aead.newKey())
        assertEquals(2, db.records().count())
        assertEquals(2, stagedFiles())
        // L'application est tuée avant la configuration de l'accès : la clé en mémoire est perdue.
        backup.cleanupIfUnconfigured()
        assertEquals(0, db.records().count())
        assertEquals(0, stagedFiles())
        assertFalse(backup.hasRecoveryPhrase)
    }

    @Test
    fun restauration_refusee_dans_une_installation_deja_configuree() {
        val archive = exportArchive()
        try {
            backup.restore(ByteArrayInputStream(archive), recovery, Aead.newKey())
            fail("restauration acceptée dans un coffre existant")
        } catch (e: IllegalStateException) {
        }
        assertEquals("le coffre existant est intact", 2, vault.loadAll().records.size)
        backup.cleanupIfUnconfigured()
        assertEquals("un coffre configuré n'est jamais effacé au démarrage", 2, db.records().count())
    }

    @Test
    fun export_annule() {
        setUpVault()
        vault.put(passport)
        store.import(passport.id, AttachmentRole.FRONT, "a.png", ByteArrayInputStream(front), front.size.toLong(), { false }) {}
        backup.saveRecoveryPhrase(recovery)
        try {
            backup.export(ByteArrayOutputStream(), recovery, { true }) {}
            fail("export non annulé")
        } catch (e: ExportCancelled) {
        }
        assertNull("un export annulé ne compte pas comme une sauvegarde", backup.lastExportMs())
    }

    @Test
    fun coffre_verrouille_pas_d_export() {
        setUpVault()
        backup.saveRecoveryPhrase(recovery)
        gate.lock()
        try {
            backup.export(ByteArrayOutputStream(), recovery, { false }) {}
            fail("export accepté coffre verrouillé")
        } catch (e: app.mespapiers.core.access.VaultLockedException) {
        }
    }
}
