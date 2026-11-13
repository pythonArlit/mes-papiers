package app.mespapiers.data

import android.content.Context
import app.mespapiers.core.access.SessionGate
import app.mespapiers.core.crypto.StreamCipher
import app.mespapiers.core.model.AttachmentMeta
import app.mespapiers.core.model.AttachmentRole
import app.mespapiers.core.model.AttachmentVault
import app.mespapiers.core.model.Ids
import app.mespapiers.core.model.Limits
import app.mespapiers.core.util.wipe
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class NoSpaceException : IOException("Espace insuffisant")

/**
 * Pièces jointes chiffrées sur disque.
 *
 * Ordre des écritures, pour ne jamais laisser de fichier orphelin référencé :
 * - ajout : fichier temporaire chiffré, synchronisé → renommage → ligne en base ;
 * - suppression : ligne en base → fichier.
 * Un arrêt brutal entre deux étapes laisse au pire un fichier chiffré que plus rien ne
 * référence ; [cleanup] le supprime au démarrage suivant.
 */
class AttachmentStore(context: Context, private val db: VaultDb, private val gate: SessionGate) {
    private val dir = File(context.noBackupFilesDir, "attachments")

    /** Photos en cours de prise de vue (en clair, le temps de la capture). */
    val captureDir = File(context.cacheDir, "capture")

    /** Copies en clair pour le rendu PDF avant Android 11. */
    val viewDir = File(context.cacheDir, "view")

    private fun file(id: String) = File(dir, "$id.enc")
    private fun thumbFile(id: String) = File(dir, "$id.thumb")

    /** Au démarrage : fichiers de travail en clair, imports interrompus et fichiers orphelins. */
    fun cleanup() {
        captureDir.deleteRecursively()
        viewDir.deleteRecursively()
        // Images laissées en clair par le scanner de documents si l'application a été tuée en plein scan.
        captureDir.parentFile?.listFiles()?.filter { it.name.startsWith("mlkit_docscan") }?.forEach { it.deleteRecursively() }
        dir.mkdirs()
        val known = db.attachments().ids().toSet()
        dir.listFiles()?.forEach { f ->
            val knownSuffix = f.name.endsWith(".enc") || f.name.endsWith(".thumb")
            val id = f.name.substringBefore('.')
            if (!knownSuffix || id !in known) f.delete()
        }
    }

    /** Efface les copies en clair temporaires (verrouillage, fermeture de la visionneuse). */
    fun clearPlainCopies() {
        viewDir.deleteRecursively()
    }

    fun loadAll(): List<AttachmentMeta> = withKey { key ->
        db.attachments().all().mapNotNull { row ->
            try {
                AttachmentVault.openMeta(key, row.id, row.blob).takeIf { file(it.id).isFile }
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Importe [source] en flux. Lève ImportException (format, taille, annulation),
     * NoSpaceException, ou IOException si la lecture ou l'écriture échoue. Dans tous ces
     * cas, aucun fichier ni aucune ligne ne subsiste.
     */
    fun import(
        recordId: String,
        role: AttachmentRole,
        originalName: String,
        source: InputStream,
        knownSize: Long,
        isCancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
    ): AttachmentMeta = withKey { key ->
        dir.mkdirs()
        if (knownSize > 0 && dir.usableSpace < StreamCipher.cipherSize(knownSize) + SPACE_MARGIN) throw NoSpaceException()
        val id = Ids.new()
        val tmp = File(dir, "$id.tmp")
        val target = file(id)
        try {
            val imported = AttachmentVault.importStream(
                key, id, source, SyncingStream(FileOutputStream(tmp)), Limits.MAX_FILE_BYTES,
                isCancelled = { isCancelled() || !gate.isOpen }, onProgress = onProgress,
            )
            if (!tmp.renameTo(target)) throw IOException("Renommage impossible")
            val meta = AttachmentMeta(id, recordId, role, imported.format, originalName.take(200), imported.plainSize)
            db.attachments().put(AttachmentRow(id, AttachmentVault.sealMeta(key, meta)))
            meta
        } catch (e: Exception) {
            tmp.delete()
            target.delete()
            if (e is IOException && e !is app.mespapiers.core.model.ImportException && dir.usableSpace < SPACE_MARGIN) throw NoSpaceException()
            throw e
        }
    }

    /** Contenu déchiffré en mémoire (20 Mo au plus). L'appelant efface le tableau après usage. */
    fun readAll(meta: AttachmentMeta): ByteArray = withKey { key ->
        AttachmentVault.decrypting(key, meta.id, file(meta.id).inputStream().buffered()).use { it.readBytes() }
    }

    /** Contenu déchiffré, en flux (export). L'appelant ferme le flux. */
    fun open(meta: AttachmentMeta): InputStream = withKey { key ->
        AttachmentVault.decrypting(key, meta.id, file(meta.id).inputStream().buffered())
    }

    /**
     * Restauration : chiffre [content] sous une clé de données fournie, coffre pas encore
     * configuré. Le fichier est nommé d'après meta.id, que l'appelant vient de tirer au
     * hasard : aucun nom de l'archive n'est utilisé. Lève ImportException si le contenu
     * n'est pas du format annoncé.
     */
    fun restore(dataKey: ByteArray, meta: AttachmentMeta, content: InputStream) {
        dir.mkdirs()
        val key = AttachmentVault.key(dataKey)
        val tmp = File(dir, "${meta.id}.tmp")
        try {
            val imported = AttachmentVault.importStream(key, meta.id, content, SyncingStream(FileOutputStream(tmp)), Limits.MAX_FILE_BYTES)
            if (imported.format != meta.format || imported.plainSize != meta.plainSize) {
                throw app.mespapiers.core.model.ImportException(app.mespapiers.core.model.ImportException.Reason.UNSUPPORTED_FORMAT)
            }
            if (!tmp.renameTo(file(meta.id))) throw IOException("Renommage impossible")
            db.attachments().put(AttachmentRow(meta.id, AttachmentVault.sealMeta(key, meta)))
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            key.wipe()
        }
    }

    /** Supprime toutes les pièces (installation remise à vide). */
    fun wipe() {
        dir.deleteRecursively()
    }

    val usableSpace: Long get() = dir.apply { mkdirs() }.usableSpace

    fun delete(meta: AttachmentMeta) {
        if (!gate.isOpen) throw app.mespapiers.core.access.VaultLockedException()
        db.attachments().delete(meta.id)
        file(meta.id).delete()
        thumbFile(meta.id).delete()
    }

    /** Enregistre l'aperçu réduit (JPEG) d'une pièce, chiffré. */
    fun putThumb(meta: AttachmentMeta, jpeg: ByteArray) = withKey { key ->
        val tmp = File(dir, "${meta.id}.thumb.tmp")
        tmp.writeBytes(AttachmentVault.sealThumb(key, meta.id, jpeg))
        if (!tmp.renameTo(thumbFile(meta.id))) tmp.delete()
    }

    /** Aperçu déchiffré, ou null s'il n'existe pas encore ou n'est pas lisible. */
    fun thumb(meta: AttachmentMeta): ByteArray? = withKey { key ->
        val f = thumbFile(meta.id)
        if (!f.isFile) null else try {
            AttachmentVault.openThumb(key, meta.id, f.readBytes())
        } catch (e: Exception) {
            null
        }
    }

    /** Copie une pièce vers une autre fiche (duplication), en flux, sous un nouvel identifiant. */
    fun copy(meta: AttachmentMeta, newRecordId: String): AttachmentMeta = withKey { key ->
        AttachmentVault.decrypting(key, meta.id, file(meta.id).inputStream().buffered()).use { plain ->
            import(newRecordId, meta.role, meta.originalName, plain, meta.plainSize, { false }, {})
        }
    }

    private fun <T> withKey(block: (ByteArray) -> T): T = gate.withDataKey { dk ->
        val key = AttachmentVault.key(dk)
        try {
            block(key)
        } finally {
            key.wipe()
        }
    }

    /** Synchronise le fichier sur le disque avant de le fermer. */
    private class SyncingStream(private val fos: FileOutputStream) : OutputStream() {
        override fun write(b: Int) = fos.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = fos.write(b, off, len)
        override fun flush() = fos.flush()
        override fun close() {
            try {
                fos.flush()
                fos.fd.sync()
            } finally {
                fos.close()
            }
        }
    }

    private companion object {
        const val SPACE_MARGIN = 2L * 1024 * 1024
    }
}
