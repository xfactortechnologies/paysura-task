package com.example.paysura.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "journal_entries")
data class JournalEntry(
    @PrimaryKey
    val transactionId: String,
    val agentId: String,
    val biller: String,
    val customerId: String,
    val amountMinorUnits: Long,
    val currency: String,
    val status: String,
    val createdAt: Long = System.currentTimeMillis()
)
