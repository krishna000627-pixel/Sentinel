package com.sentinel.app

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity data class ScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, val pkg: String, val score: Int, val text: String, val time: Long
)

@Entity data class BlockEntity(@PrimaryKey val pkg: String)

/** Apps the user has marked as safe: no alerts, not counted as risky. */
@Entity data class SafeEntity(@PrimaryKey val pkg: String)

data class PkgTime(val pkg: String, val time: Long)

@Dao abstract class SDao {
    @Query("SELECT * FROM ScanEntity ORDER BY time DESC") abstract fun scans(): Flow<List<ScanEntity>>
    @Insert abstract suspend fun insert(s: ScanEntity)
    @Query("SELECT * FROM ScanEntity WHERE pkg = :pkg ORDER BY time DESC LIMIT 1") abstract suspend fun latest(pkg: String): ScanEntity?
    @Query("UPDATE ScanEntity SET time = :t WHERE id = :id") abstract suspend fun touch(id: Long, t: Long)

    /** Same app with the same result as its newest entry: refresh that entry's time instead of adding a copy. */
    @Transaction open suspend fun add(s: ScanEntity) {
        val l = latest(s.pkg)
        if (l != null && l.score == s.score && l.text == s.text) touch(l.id, s.time) else insert(s)
    }

    @Query("DELETE FROM ScanEntity") abstract suspend fun clear()
    @Query("SELECT pkg AS pkg, MAX(time) AS time FROM ScanEntity GROUP BY pkg") abstract suspend fun lastScans(): List<PkgTime>
    @Query("SELECT pkg FROM BlockEntity") abstract fun blocks(): Flow<List<String>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun block(b: BlockEntity)
    @Delete abstract suspend fun unblock(b: BlockEntity)

    @Query("SELECT pkg FROM SafeEntity") abstract fun safes(): Flow<List<String>>
    @Query("SELECT pkg FROM SafeEntity") abstract suspend fun safeNow(): List<String>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun markSafe(b: SafeEntity)
    @Delete abstract suspend fun unmarkSafe(b: SafeEntity)
    @Query("DELETE FROM SafeEntity") abstract suspend fun clearSafe()
}

@Database(entities = [ScanEntity::class, BlockEntity::class, SafeEntity::class], version = 2, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): SDao
    companion object {
        private val M12 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `SafeEntity` (`pkg` TEXT NOT NULL, PRIMARY KEY(`pkg`))")
                // one-time cleanup of the repeated results already in History (keeps the newest of each)
                db.execSQL("DELETE FROM ScanEntity WHERE id NOT IN (SELECT MAX(id) FROM ScanEntity GROUP BY pkg, score, text)")
            }
        }
        @Volatile private var i: Db? = null
        fun get(c: Context): Db = i ?: synchronized(this) {
            i ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "sentinel.db").addMigrations(M12).build().also { i = it }
        }
    }
}
