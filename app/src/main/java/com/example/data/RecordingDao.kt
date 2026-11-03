package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings WHERE isInTrash = 0 ORDER BY createdAt DESC")
    fun getAllRecordings(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE storageLocation = 'LOCAL' AND isInTrash = 0 ORDER BY createdAt DESC")
    fun getLocalRecordings(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE storageLocation = 'CLOUD' AND filePath != '' AND isInTrash = 0 ORDER BY createdAt DESC")
    fun getCloudRecordings(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE isInTrash = 1 ORDER BY trashedAt DESC")
    fun getTrashRecordings(): Flow<List<RecordingEntity>>

    @Query("SELECT COUNT(*) FROM recordings WHERE isInTrash = 1")
    fun getTrashCount(): Flow<Int>

    @Query("SELECT * FROM recordings WHERE id = :id LIMIT 1")
    suspend fun getRecordingById(id: Long): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE filePath = :filePath")
    suspend fun getRecordingsByFilePath(filePath: String): List<RecordingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecording(recording: RecordingEntity): Long

    @Update
    suspend fun updateRecording(recording: RecordingEntity)

    @Delete
    suspend fun deleteRecording(recording: RecordingEntity)

    @Query("DELETE FROM recordings WHERE id IN (:ids)")
    suspend fun deleteRecordingsByIds(ids: List<Long>)

    @Query("UPDATE recordings SET isInTrash = 1, trashedAt = :timestamp WHERE id = :id")
    suspend fun moveToTrash(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE recordings SET isInTrash = 1, trashedAt = :timestamp WHERE id IN (:ids)")
    suspend fun moveMultipleToTrash(ids: List<Long>, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE recordings SET isInTrash = 0, trashedAt = NULL WHERE id = :id")
    suspend fun restoreFromTrash(id: Long)

    @Query("UPDATE recordings SET isInTrash = 0, trashedAt = NULL WHERE id IN (:ids)")
    suspend fun restoreMultipleFromTrash(ids: List<Long>)

    @Query("DELETE FROM recordings WHERE isInTrash = 1")
    suspend fun emptyTrashRecords(): Int

    @Query("SELECT * FROM recordings WHERE isInTrash = 1")
    suspend fun getAllTrashRecordsSync(): List<RecordingEntity>

    @Query("SELECT COUNT(*) FROM recordings WHERE isInTrash = 0")
    suspend fun getRecordingCount(): Int
}
