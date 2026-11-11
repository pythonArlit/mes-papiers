package app.mespapiers.core.model

import app.mespapiers.core.util.Bin
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeParseException

enum class Category(val id: Int) {
    IDENTITY(1),
    DIPLOMA(2),
    CIVIL_STATUS(3),
    ADDRESS(4),
    BANK(5),
    OTHER(6),
    ;

    companion object {
        fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: throw IOException("Catégorie inconnue")
    }
}

enum class FieldType(val id: Int) {
    TEXT(1),
    DATE(2),

    /** Toujours une chaîne : les zéros initiaux sont conservés. */
    IDENTIFIER(3),
    ;

    companion object {
        fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: throw IOException("Type de champ inconnu")
    }
}

/**
 * Champ d'une fiche.
 * @param key clé stable : celle du gabarit de la catégorie, ou un identifiant libre si [custom].
 * @param label libellé saisi par l'utilisateur pour un champ personnalisé (sinon vide : le
 *   libellé vient des ressources de l'application).
 * @param value texte, identifiant, ou date civile ISO (AAAA-MM-JJ) pour [FieldType.DATE].
 */
data class Field(
    val key: String,
    val type: FieldType,
    val value: String,
    val sensitive: Boolean = false,
    val custom: Boolean = false,
    val label: String = "",
) {
    fun dateOrNull(): LocalDate? =
        if (type != FieldType.DATE || value.isBlank()) null else try {
            LocalDate.parse(value)
        } catch (e: DateTimeParseException) {
            null
        }
}

/** Fiche. Un titre et une catégorie suffisent. Les dates sont civiles, sans heure ni fuseau. */
data class Record(
    val id: String,
    val category: Category,
    val title: String,
    val tags: List<String> = emptyList(),
    val favorite: Boolean = false,
    val fields: List<Field> = emptyList(),
    val expiry: LocalDate? = null,
    val createdAtMs: Long = 0,
    val updatedAtMs: Long = 0,
) {
    init {
        require(Ids.isValid(id)) { "Identifiant de fiche invalide" }
    }
}

enum class AttachmentRole(val id: Int) {
    FRONT(1),
    BACK(2),
    DOCUMENT(3),
    PROOF(4),
    OTHER(5),
    ;

    companion object {
        fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: throw IOException("Rôle de pièce inconnu")
    }
}

enum class FileFormat(val id: Int, val mime: String, val extension: String) {
    JPEG(1, "image/jpeg", "jpg"),
    PNG(2, "image/png", "png"),
    PDF(3, "application/pdf", "pdf"),
    ;

    companion object {
        fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: throw IOException("Format de fichier inconnu")
    }
}

/**
 * Métadonnées d'une pièce jointe. Le fichier chiffré est nommé d'après [id] par
 * l'application ; [originalName] n'est qu'un libellé affiché, jamais un chemin.
 */
data class AttachmentMeta(
    val id: String,
    val recordId: String,
    val role: AttachmentRole,
    val format: FileFormat,
    val originalName: String,
    val plainSize: Long,
) {
    init {
        require(Ids.isValid(id) && Ids.isValid(recordId)) { "Identifiant de pièce invalide" }
        require(plainSize >= 0)
    }
}

object Ids {
    private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    fun new(): String = java.util.UUID.randomUUID().toString()

    /** Seul un UUID en minuscules est accepté : un identifiant ne peut jamais former un chemin. */
    fun isValid(id: String): Boolean = UUID_PATTERN.matches(id)
}

object Limits {
    const val MAX_FILE_BYTES = 20L * 1024 * 1024
    const val MAX_ATTACHMENTS_PER_RECORD = 10
    const val MAX_RECORDS = 5_000
    const val MAX_TOTAL_ATTACHMENT_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_FIELDS = 200
    const val MAX_TAGS = 50
    const val MAX_RECORD_BYTES = 1024 * 1024
}

/**
 * Sérialisation binaire versionnée des fiches et des métadonnées. Le résultat est toujours
 * chiffré avant d'être écrit (base ou archive).
 */
object Codec {
    private const val RECORD_VERSION = 1
    private const val ATTACHMENT_VERSION = 1

    fun encodeRecord(r: Record): ByteArray {
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        out.writeByte(RECORD_VERSION)
        Bin.writeString(out, r.id)
        out.writeByte(r.category.id)
        Bin.writeString(out, r.title)
        out.writeBoolean(r.favorite)
        Bin.writeString(out, r.expiry?.toString() ?: "")
        out.writeLong(r.createdAtMs)
        out.writeLong(r.updatedAtMs)
        out.writeInt(r.tags.size)
        r.tags.forEach { Bin.writeString(out, it) }
        out.writeInt(r.fields.size)
        for (f in r.fields) {
            Bin.writeString(out, f.key)
            out.writeByte(f.type.id)
            Bin.writeString(out, f.value)
            out.writeBoolean(f.sensitive)
            out.writeBoolean(f.custom)
            Bin.writeString(out, f.label)
        }
        return bos.toByteArray()
    }

    fun decodeRecord(bytes: ByteArray): Record {
        try {
            val inp = DataInputStream(ByteArrayInputStream(bytes))
            if (inp.readUnsignedByte() != RECORD_VERSION) throw IOException("Version de fiche inconnue")
            val id = Bin.readString(inp)
            val category = Category.fromId(inp.readUnsignedByte())
            val title = Bin.readString(inp)
            val favorite = inp.readBoolean()
            val expiryText = Bin.readString(inp)
            val created = inp.readLong()
            val updated = inp.readLong()
            val tagCount = inp.readInt()
            if (tagCount !in 0..Limits.MAX_TAGS) throw IOException("Trop d'étiquettes")
            val tags = List(tagCount) { Bin.readString(inp) }
            val fieldCount = inp.readInt()
            if (fieldCount !in 0..Limits.MAX_FIELDS) throw IOException("Trop de champs")
            val fields = List(fieldCount) {
                Field(
                    key = Bin.readString(inp),
                    type = FieldType.fromId(inp.readUnsignedByte()),
                    value = Bin.readString(inp),
                    sensitive = inp.readBoolean(),
                    custom = inp.readBoolean(),
                    label = Bin.readString(inp),
                )
            }
            val expiry = if (expiryText.isEmpty()) null else LocalDate.parse(expiryText)
            return Record(id, category, title, tags, favorite, fields, expiry, created, updated)
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            throw IOException("Fiche illisible")
        }
    }

    fun encodeAttachment(a: AttachmentMeta): ByteArray {
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        out.writeByte(ATTACHMENT_VERSION)
        Bin.writeString(out, a.id)
        Bin.writeString(out, a.recordId)
        out.writeByte(a.role.id)
        out.writeByte(a.format.id)
        Bin.writeString(out, a.originalName)
        out.writeLong(a.plainSize)
        return bos.toByteArray()
    }

    fun decodeAttachment(bytes: ByteArray): AttachmentMeta {
        try {
            val inp = DataInputStream(ByteArrayInputStream(bytes))
            if (inp.readUnsignedByte() != ATTACHMENT_VERSION) throw IOException("Version de pièce inconnue")
            return AttachmentMeta(
                id = Bin.readString(inp),
                recordId = Bin.readString(inp),
                role = AttachmentRole.fromId(inp.readUnsignedByte()),
                format = FileFormat.fromId(inp.readUnsignedByte()),
                originalName = Bin.readString(inp),
                plainSize = inp.readLong(),
            )
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            throw IOException("Pièce illisible")
        }
    }
}

/** Champ proposé par défaut pour une catégorie. */
data class FieldSpec(
    val key: String,
    val type: FieldType,
    val sensitive: Boolean = false,
    /** Vrai si ce champ alimente l'échéance de la fiche. */
    val isExpiry: Boolean = false,
)

/**
 * Gabarits de la section 3. Tous les champs sont facultatifs. Aucun champ n'est prévu pour
 * un secret de paiement ou de connexion (code de carte, cryptogramme, mot de passe).
 */
object Templates {
    fun fieldsFor(category: Category): List<FieldSpec> = when (category) {
        Category.IDENTITY -> listOf(
            FieldSpec("type", FieldType.TEXT),
            FieldSpec("holder", FieldType.TEXT),
            FieldSpec("number", FieldType.IDENTIFIER, sensitive = true),
            FieldSpec("issuer", FieldType.TEXT),
            FieldSpec("issued_on", FieldType.DATE),
            FieldSpec("expires_on", FieldType.DATE, isExpiry = true),
        )
        Category.DIPLOMA -> listOf(
            FieldSpec("title", FieldType.TEXT),
            FieldSpec("institution", FieldType.TEXT),
            FieldSpec("obtained_on", FieldType.DATE),
            FieldSpec("reference", FieldType.IDENTIFIER),
        )
        Category.CIVIL_STATUS -> listOf(
            FieldSpec("act_type", FieldType.TEXT),
            FieldSpec("holder", FieldType.TEXT),
            FieldSpec("act_date", FieldType.DATE),
            FieldSpec("reference", FieldType.IDENTIFIER),
        )
        Category.ADDRESS -> listOf(
            FieldSpec("label", FieldType.TEXT),
            FieldSpec("address", FieldType.TEXT),
            FieldSpec("city", FieldType.TEXT),
            FieldSpec("country", FieldType.TEXT),
            FieldSpec("extra", FieldType.TEXT),
            FieldSpec("valid_from", FieldType.DATE),
            FieldSpec("valid_until", FieldType.DATE, isExpiry = true),
        )
        Category.BANK -> listOf(
            FieldSpec("bank", FieldType.TEXT),
            FieldSpec("holder", FieldType.TEXT),
            FieldSpec("country", FieldType.TEXT),
            FieldSpec("account_number", FieldType.IDENTIFIER, sensitive = true),
            FieldSpec("rib", FieldType.IDENTIFIER, sensitive = true),
            FieldSpec("iban", FieldType.IDENTIFIER, sensitive = true),
            FieldSpec("bic", FieldType.IDENTIFIER),
        )
        Category.OTHER -> listOf(
            FieldSpec("reference", FieldType.IDENTIFIER),
            FieldSpec("contact", FieldType.TEXT),
            FieldSpec("valid_until", FieldType.DATE, isExpiry = true),
        )
    }

    /** Rôles de pièce jointe proposés en premier pour la catégorie. */
    fun rolesFor(category: Category): List<AttachmentRole> = when (category) {
        Category.IDENTITY -> listOf(AttachmentRole.FRONT, AttachmentRole.BACK)
        Category.ADDRESS, Category.BANK -> listOf(AttachmentRole.PROOF)
        else -> listOf(AttachmentRole.DOCUMENT)
    }
}
