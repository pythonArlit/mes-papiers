package app.mespapiers.data

import android.content.Context
import app.mespapiers.core.access.SessionGate
import app.mespapiers.core.archive.ArchiveContent
import app.mespapiers.core.archive.ArchiveError
import app.mespapiers.core.archive.ArchiveException
import app.mespapiers.core.archive.ArchiveReader
import app.mespapiers.core.archive.ArchiveSummary
import app.mespapiers.core.archive.ArchiveWriter
import app.mespapiers.core.archive.AttachmentSource
import app.mespapiers.core.archive.RecoveryRecord
import app.mespapiers.core.archive.RestoreSink
import app.mespapiers.core.model.AttachmentMeta
import app.mespapiers.core.model.Ids
import app.mespapiers.core.model.ImportException
import app.mespapiers.core.model.Record
import app.mespapiers.core.util.Clock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ExportCancelled : IOException("Export annulé")

/**
 * Sauvegarde et restauration.
 *
 * Export : archive chiffrée sous une clé dérivée de la seule phrase de récupération ;
 * aucune clé du téléphone, ni la graine TOTP, ni la phrase d'accès n'y figurent.
 *
 * Restauration, uniquement dans une installation vide : le contenu de l'archive est
 * vérifié et écrit, chiffré sous une clé de données neuve gardée en mémoire, alors que le
 * coffre n'existe pas encore. Il ne devient accessible que lorsque l'utilisateur a choisi
 * sa phrase d'accès et confirmé son code : c'est l'écriture atomique de l'état d'accès
 * (SessionGate.confirmSetup) qui fait la bascule. Avant cela, toute erreur, tout abandon
 * ou tout arrêt de l'application laisse une installation vide ([wipeAll], [cleanupIfUnconfigured]).
 */
class BackupManager(
    context: Context,
    private val db: VaultDb,
    private val gate: SessionGate,
    private val vault: Vault,
    private val attachments: AttachmentStore,
    private val clock: Clock,
) {
    private val recoveryFile = File(context.noBackupFilesDir, "recovery.bin")

    /** Date du dernier export, en clair : elle sert au rappel affiché coffre verrouillé (MENACES.md). */
    private val lastExportFile = File(context.noBackupFilesDir, "last-export")

    class ExportResult(val records: Int, val attachments: Int, val unreadable: Int)

    val hasRecoveryPhrase: Boolean get() = recoveryFile.isFile

    fun lastExportMs(): Long? = try {
        lastExportFile.takeIf { it.isFile }?.readText()?.trim()?.toLong()
    } catch (e: Exception) {
        null
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(bytes)
            it.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Enregistre la trace chiffrée de la phrase de récupération (jamais la phrase elle-même). */
    fun saveRecoveryPhrase(normalized: CharArray) = gate.withDataKey { dk -> writeAtomically(recoveryFile, RecoveryRecord.seal(dk, normalized)) }

    fun checkRecoveryPhrase(normalized: CharArray): Boolean = gate.withDataKey { dk ->
        recoveryFile.isFile && RecoveryRecord.matches(dk, recoveryFile.readBytes(), normalized)
    }

    /**
     * Écrit l'archive dans [out] (fermé à la fin). En cas d'exception, l'appelant supprime
     * le fichier partiel. [isCancelled] est consulté pendant la copie des pièces.
     */
    fun export(out: OutputStream, normalized: CharArray, isCancelled: () -> Boolean, onProgress: (Long) -> Unit): ExportResult {
        val loaded = vault.loadAll()
        val ids = loaded.records.map { it.id }.toSet()
        val metas = attachments.loadAll().filter { it.recordId in ids }
        val now = clock.wallMillis()
        val content = ArchiveContent(
            records = loaded.records,
            attachments = metas.map { m -> AttachmentSource(m) { attachments.open(m) } },
            exportedAtMs = now,
        )
        ArchiveWriter.write(out, normalized, content) { done ->
            if (isCancelled()) throw ExportCancelled()
            onProgress(done)
        }
        writeAtomically(lastExportFile, now.toString().toByteArray())
        return ExportResult(loaded.records.size, metas.size, loaded.unreadable)
    }

    /**
     * Lit, vérifie et prépare l'archive sous [dataKey]. Rien n'est accessible tant que le
     * coffre n'est pas configuré avec cette clé. Toute anomalie remet l'installation à vide
     * et lève [ArchiveException].
     */
    fun restore(input: InputStream, normalized: CharArray, dataKey: ByteArray): ArchiveSummary {
        check(!gate.isConfigured) { "La restauration n'est possible que dans une installation vide" }
        wipeAll()
        // Les identifiants de l'archive ne servent qu'à relier pièces et fiches : tout est renuméroté.
        val recordIds = HashMap<String, String>()
        val sink = object : RestoreSink {
            override fun settings(bytes: ByteArray) = Unit

            override fun record(record: Record) {
                val id = Ids.new()
                recordIds[record.id] = id
                vault.restoreRecord(dataKey, record.copy(id = id))
            }

            override fun attachment(meta: AttachmentMeta, content: InputStream) {
                val recordId = recordIds[meta.recordId] ?: throw ArchiveException(ArchiveError.CORRUPTED)
                try {
                    attachments.restore(dataKey, meta.copy(id = Ids.new(), recordId = recordId), content)
                } catch (e: ImportException) {
                    throw ArchiveException(ArchiveError.CORRUPTED, e)
                }
            }
        }
        try {
            val summary = input.use { ArchiveReader.restore(it, normalized, sink) }
            // La personne a prouvé qu'elle détient la phrase : elle servira aux prochains exports.
            writeAtomically(recoveryFile, RecoveryRecord.seal(dataKey, normalized))
            writeAtomically(lastExportFile, summary.exportedAtMs.toString().toByteArray())
            return summary
        } catch (e: Exception) {
            wipeAll()
            throw e as? ArchiveException ?: ArchiveException(ArchiveError.STORAGE, e)
        }
    }

    /** Remet l'installation à vide : fiches, pièces, trace de la phrase de récupération. */
    fun wipeAll() {
        db.clearAllTables()
        attachments.wipe()
        recoveryFile.delete()
        lastExportFile.delete()
    }

    /** Au démarrage : efface une restauration préparée mais jamais activée. */
    fun cleanupIfUnconfigured() {
        if (!gate.isConfigured) wipeAll()
    }
}
