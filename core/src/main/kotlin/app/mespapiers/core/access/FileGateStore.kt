package app.mespapiers.core.access

import app.mespapiers.core.crypto.KdfParams
import app.mespapiers.core.util.Bin
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * État d'accès sur disque, dans un répertoire privé exclu des sauvegardes.
 *
 * Chaque écriture passe par un fichier temporaire synchronisé sur le disque puis un
 * renommage atomique : après un arrêt brutal on retrouve soit l'ancien état, soit le
 * nouveau, jamais un fichier à moitié écrit.
 *
 * Un fichier absent signifie « jamais écrit ». Un fichier présent mais illisible lève
 * [IOException] : l'appelant ne doit surtout pas en conclure que le coffre n'est pas
 * configuré (il recréerait les clés et détruirait l'accès aux données).
 *
 * Contenu lisible hors session : voir MENACES.md.
 */
class FileGateStore(private val dir: File) : GateStore {
    private val gateFile = File(dir, "gate.bin")
    private val backoffFile = File(dir, "backoff.bin")
    private val stepFile = File(dir, "totp-step.bin")

    init {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Répertoire d'état inaccessible")
    }

    @Synchronized
    override fun load(): GateRecord? = read(gateFile, MAGIC_GATE) { inp ->
        GateRecord(
            KdfParams.decode(Bin.readBytes(inp, 256)),
            Bin.readBytes(inp, MAX_BLOB),
            Bin.readBytes(inp, MAX_BLOB),
        )
    }

    @Synchronized
    override fun save(record: GateRecord) = write(gateFile, MAGIC_GATE) { out ->
        Bin.writeBytes(out, record.kdf.encode())
        Bin.writeBytes(out, record.seedBlob)
        Bin.writeBytes(out, record.dataKeyBlob)
    }

    @Synchronized
    override fun loadBackoff(): BackoffState = read(backoffFile, MAGIC_BACKOFF) { inp ->
        BackoffState(inp.readInt(), inp.readLong(), inp.readLong(), inp.readInt())
    } ?: BackoffState()

    @Synchronized
    override fun saveBackoff(state: BackoffState) = write(backoffFile, MAGIC_BACKOFF) { out ->
        out.writeInt(state.failures)
        out.writeLong(state.delayMs)
        out.writeLong(state.lockedUntilElapsed)
        out.writeInt(state.bootCount)
    }

    @Synchronized
    override fun lastTotpStep(): Long = read(stepFile, MAGIC_STEP) { it.readLong() } ?: Long.MIN_VALUE

    @Synchronized
    override fun advanceTotpStep(step: Long): Boolean {
        if (step <= lastTotpStep()) return false
        write(stepFile, MAGIC_STEP) { it.writeLong(step) }
        return true
    }

    /** Efface tout l'état d'accès (réinitialisation d'une installation, tests). */
    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun <T> read(file: File, magic: Int, block: (DataInputStream) -> T): T? {
        if (!file.exists()) return null
        try {
            DataInputStream(file.inputStream().buffered()).use { inp ->
                if (inp.readInt() != magic) throw IOException("Fichier d'état inattendu : ${file.name}")
                return block(inp)
            }
        } catch (e: EOFException) {
            throw IOException("Fichier d'état tronqué : ${file.name}")
        }
    }

    private fun write(file: File, magic: Int, block: (DataOutputStream) -> Unit) {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(magic)
            block(out)
        }
        val tmp = File(dir, file.name + ".tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(bos.toByteArray())
            fos.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        const val MAGIC_GATE = 0x4D504731 // "MPG1"
        const val MAGIC_BACKOFF = 0x4D504231 // "MPB1"
        const val MAGIC_STEP = 0x4D505331 // "MPS1"
        const val MAX_BLOB = 4096
    }
}
