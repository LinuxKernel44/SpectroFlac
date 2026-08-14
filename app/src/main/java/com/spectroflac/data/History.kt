package com.spectroflac.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.spectroflac.analysis.AnalysisReport
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

@Entity(tableName = "analyses")
data class AnalysisRecord(
    @PrimaryKey val uri: String,
    val fileName: String,
    val verdict: String,
    val confidence: Int,
    val headline: String,
    val artist: String?,
    val title: String?,
    val analysedAt: Long,
    val json: String,
)

@Dao
interface AnalysisDao {
    @Query("SELECT * FROM analyses ORDER BY analysedAt DESC LIMIT 500")
    fun observeAll(): Flow<List<AnalysisRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: AnalysisRecord)

    @Query("DELETE FROM analyses WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM analyses")
    suspend fun clear()

    @Query("SELECT * FROM analyses ORDER BY analysedAt DESC LIMIT 500")
    suspend fun all(): List<AnalysisRecord>
}

@Database(entities = [AnalysisRecord::class], version = 1, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun analyses(): AnalysisDao

    companion object {
        @Volatile
        private var instance: HistoryDatabase? = null

        fun get(context: Context): HistoryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                HistoryDatabase::class.java,
                "spectroflac-history",
            ).fallbackToDestructiveMigration(dropAllTables = true).build().also { instance = it }
        }
    }
}

fun AnalysisReport.toRecord() = AnalysisRecord(
    uri = uri,
    fileName = fileName,
    verdict = verdict.name,
    confidence = confidence,
    headline = headline,
    artist = artist,
    title = title,
    analysedAt = analysedAtMillis,
    json = ReportJson.toJson(this).toString(),
)

fun AnalysisRecord.toReport(): AnalysisReport = ReportJson.fromJson(JSONObject(json))
