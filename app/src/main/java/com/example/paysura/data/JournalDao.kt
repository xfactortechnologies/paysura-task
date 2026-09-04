package com.example.paysura.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface JournalDao {
    @Insert
    suspend fun insert(entry: JournalEntry)

    @Query("UPDATE journal_entries SET status = :status WHERE transactionId = :transactionId")
    suspend fun updateStatus(transactionId: String, status: String)

    @Query("SELECT * FROM journal_entries WHERE transactionId = :transactionId")
    fun getEntryFlow(transactionId: String): Flow<JournalEntry?>

    @Query("SELECT * FROM journal_entries WHERE status IN ('PENDING', 'INDETERMINATE')")
    suspend fun getPendingEntries(): List<JournalEntry>

    @Query("SELECT * FROM journal_entries ORDER BY createdAt DESC")
    fun getAllEntriesFlow(): Flow<List<JournalEntry>>
}
