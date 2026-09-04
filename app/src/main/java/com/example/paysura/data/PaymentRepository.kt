package com.example.paysura.data

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID
import java.math.BigDecimal

class PaymentRepository(
    private val api: PaymentApi,
    private val journalDao: JournalDao,
    private val applicationScope: CoroutineScope,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun payBill(
        amountMinorUnits: Long,
        customerId: String,
        agentId: String = "AGT-0001",
        biller: String = "mtn",
        currency: String = "SSP",
        existingTransactionId: String? = null
    ): String {
        val transactionId = existingTransactionId ?: UUID.randomUUID().toString()

        val entry = JournalEntry(
            transactionId = transactionId,
            agentId = agentId,
            biller = biller,
            customerId = customerId,
            amountMinorUnits = amountMinorUnits,
            currency = currency,
            status = "PENDING"
        )
        // Save intent to DB BEFORE returning to ensure it's journaled before network leaves
        journalDao.insert(entry)

        // Make network call in applicationScope so it outlives UI
        applicationScope.launch(defaultDispatcher) {
            executeNetworkCall(entry)
        }

        return transactionId
    }

    private suspend fun executeNetworkCall(entry: JournalEntry) {
        try {
            val request = PayBillRequest(
                transactionId = entry.transactionId,
                agentId = entry.agentId,
                biller = entry.biller,
                customerId = entry.customerId,
                amount = BigDecimal(entry.amountMinorUnits).divide(BigDecimal(100)),
                currency = entry.currency
            )
            val response = api.payBill(request)
            
            if (response.isSuccessful) {
                val body = response.body()
                if (body?.message != null) {
                    val status = body.message.status.uppercase()
                    journalDao.updateStatus(entry.transactionId, status)
                } else {
                    journalDao.updateStatus(entry.transactionId, "FAILED")
                }
            } else {
                val errorBody = response.errorBody()?.string()
                if (errorBody != null) {
                    try {
                        val json = Json { ignoreUnknownKeys = true }
                        val envelope = json.decodeFromString<PaymentResponseEnvelope>(errorBody)
                        val status = when (envelope.error) {
                            "insufficient_balance", "amount_invalid", "customer_id_required", "transaction_id_required" -> "DECLINED"
                            "jwt_expired", "invalid_json", "method_not_found" -> "INDETERMINATE"
                            else -> "INDETERMINATE"
                        }
                        journalDao.updateStatus(entry.transactionId, status)
                    } catch (e: Exception) {
                        Log.e("PaymentRepo", "Failed to parse error body", e)
                        journalDao.updateStatus(entry.transactionId, "INDETERMINATE")
                    }
                } else {
                    journalDao.updateStatus(entry.transactionId, "INDETERMINATE")
                }
            }
        } catch (e: Exception) {
            Log.e("PaymentRepo", "Network error", e)
            journalDao.updateStatus(entry.transactionId, "INDETERMINATE")
        }
    }

    suspend fun resolvePendingTransactions() {
        val pendingEntries = journalDao.getPendingEntries()
        for (entry in pendingEntries) {
            try {
                val request = TransactionStatusRequest(
                    transactionId = entry.transactionId,
                    agentId = entry.agentId
                )
                val response = api.getTransactionStatus(request)
                if (response.isSuccessful) {
                    val status = response.body()?.message?.status
                    if (status != null && status != "pending") {
                        journalDao.updateStatus(entry.transactionId, status.uppercase())
                    }
                } else {
                     val errorBody = response.errorBody()?.string()
                     if (errorBody?.contains("not_found") == true) {
                         // Still unknown
                     } else {
                         // other errors?
                     }
                }
            } catch (e: Exception) {
                // Ignore, try next time
            }
        }
    }

    fun getEntryFlow(transactionId: String): Flow<JournalEntry?> {
        return journalDao.getEntryFlow(transactionId)
    }

    fun getAllEntriesFlow(): Flow<List<JournalEntry>> {
        return journalDao.getAllEntriesFlow()
    }
}
