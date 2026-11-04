package com.sentinel.app

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity data class ScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, val pkg: String, val score: Int, val text: String, val time: Long
)

@Entity data class BlockEntity(@PrimaryKey val pkg: String)

@Dao interface SDao {
    @Query("SELECT * FROM ScanEntity ORDER BY time DESC") fun scans(): Flow<List<ScanEntity>>
    @Insert suspend fun add(s: ScanEntity)
    @Query("DELETE FROM ScanEntity") suspend fun clear()
    @Query("SELECT pkg FROM BlockEntity") fun blocks(): Flow<List<String>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun block(b: BlockEntity)
    @Delete suspend fun unblock(b: BlockEntity)
}

@Database(entities = [ScanEntity::class, BlockEntity::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): SDao
    companion object {
        @Volatile private var i: Db? = null
        fun get(c: Context): Db = i ?: synchronized(this) {
            i ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "sentinel.db").build().also { i = it }
        }
    }
}
