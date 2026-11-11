package app.mespapiers.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import app.mespapiers.core.access.SessionGate
import app.mespapiers.core.docs.SearchIndex
import app.mespapiers.core.model.Record
import app.mespapiers.core.model.RecordVault
import app.mespapiers.core.util.wipe

/**
 * Une ligne = une fiche chiffrée en bloc. La seule colonne en clair est un identifiant
 * aléatoire : le fichier de base, son journal et ses index ne contiennent aucun titre,
 * aucune catégorie, aucune date.
 */
@Entity(tableName = "records")
class RecordRow(@PrimaryKey val id: String, val blob: ByteArray)

@Dao
interface RecordDao {
    @Query("SELECT * FROM records")
    fun all(): List<RecordRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(row: RecordRow)

    @Query("DELETE FROM records WHERE id = :id")
    fun delete(id: String)

    @Query("SELECT COUNT(*) FROM records")
    fun count(): Int
}

@Database(entities = [RecordRow::class], version = 1, exportSchema = false)
abstract class VaultDb : RoomDatabase() {
    abstract fun records(): RecordDao
}

/** Accès aux fiches. Toutes les fonctions lèvent VaultLockedException si la session est verrouillée. */
class Vault(private val db: VaultDb, private val gate: SessionGate) {

    class Loaded(val records: List<Record>, val unreadable: Int)

    /** Déchiffre toutes les fiches. Une ligne abîmée est comptée, pas masquée. */
    fun loadAll(): Loaded = gate.withDataKey { dk ->
        val key = RecordVault.key(dk)
        try {
            var unreadable = 0
            val records = db.records().all().mapNotNull { row ->
                try {
                    RecordVault.open(key, row.id, row.blob)
                } catch (e: Exception) {
                    unreadable++
                    null
                }
            }
            Loaded(records, unreadable)
        } finally {
            key.wipe()
        }
    }

    fun put(record: Record) = gate.withDataKey { dk ->
        val key = RecordVault.key(dk)
        try {
            db.records().put(RecordRow(record.id, RecordVault.seal(key, record)))
        } finally {
            key.wipe()
        }
    }

    fun delete(id: String) {
        if (!gate.isOpen) throw app.mespapiers.core.access.VaultLockedException()
        db.records().delete(id)
    }
}

/**
 * Fiches déchiffrées et index de recherche, en mémoire pendant la session seulement.
 * [clear] est appelé à chaque verrouillage.
 */
class VaultState {
    var records by mutableStateOf<List<Record>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set
    var unreadable by mutableStateOf(0)
        private set
    private val index = SearchIndex()

    fun set(list: List<Record>, unreadableCount: Int, categoryLabel: (Record) -> String) {
        records = list.sortedBy { it.title.lowercase() }
        unreadable = unreadableCount
        index.rebuild(records, categoryLabel)
        loaded = true
    }

    fun search(query: String): Set<String> = index.search(query).toSet()

    fun clear() {
        records = emptyList()
        unreadable = 0
        loaded = false
        index.clear()
    }
}
