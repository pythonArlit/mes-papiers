package app.mespapiers.core.model

import app.mespapiers.core.crypto.Aead
import app.mespapiers.core.crypto.AeadException
import app.mespapiers.core.crypto.CryptoContext
import app.mespapiers.core.util.Hkdf

/**
 * Chiffrement des fiches pour la base : une fiche entière = un enregistrement chiffré,
 * lié à son identifiant. Titre, étiquettes, champs, catégorie et échéance sont tous dans
 * le chiffré ; la base ne contient aucune colonne en clair hormis l'identifiant aléatoire.
 */
object RecordVault {
    /** Sous-clé dédiée aux fiches, dérivée de la clé de données. À effacer après usage. */
    fun key(dataKey: ByteArray): ByteArray = Hkdf.derive(dataKey, null, "mp/records/v1")

    fun seal(recordsKey: ByteArray, record: Record): ByteArray =
        Aead.seal(recordsKey, Codec.encodeRecord(record), CryptoContext.of("record", record.id))

    /** Lève [AeadException] si le chiffré a été modifié ou déplacé sous un autre identifiant. */
    fun open(recordsKey: ByteArray, id: String, blob: ByteArray): Record {
        val record = Codec.decodeRecord(Aead.open(recordsKey, blob, CryptoContext.of("record", id)))
        if (record.id != id) throw AeadException()
        return record
    }
}
