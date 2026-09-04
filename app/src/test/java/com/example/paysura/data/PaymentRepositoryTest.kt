package com.example.paysura.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentRepositoryTest {

    @Test
    fun testPayBillCompletesAfterUIStopsObserving() = runTest {
        // Arrange
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(testDispatcher)
        
        var apiCalled = false
        var statusUpdatedTo = ""

        val fakeApi = object : PaymentApi {
            override suspend fun payBill(request: PayBillRequest): Response<PaymentResponseEnvelope> {
                apiCalled = true
                // Simulate a very slow network call (e.g. 15 seconds), longer than UI budget
                delay(15000)
                return Response.success(PaymentResponseEnvelope(
                    message = PaymentResponseData(
                        status = "SUCCESS",
                        transactionId = request.transactionId,
                        currency = "SSP"
                    )
                ))
            }

            override suspend fun getTransactionStatus(request: TransactionStatusRequest): Response<PaymentResponseEnvelope> {
                throw NotImplementedError()
            }
        }

        var savedEntry: JournalEntry? = null
        val fakeDao = object : JournalDao {
            override suspend fun insert(entry: JournalEntry) {
                savedEntry = entry
            }

            override suspend fun updateStatus(transactionId: String, status: String) {
                if (savedEntry?.transactionId == transactionId) {
                    savedEntry = savedEntry?.copy(status = status)
                    statusUpdatedTo = status
                }
            }

            override fun getEntryFlow(transactionId: String): Flow<JournalEntry?> {
                return flowOf(savedEntry)
            }

            override suspend fun getPendingEntries(): List<JournalEntry> {
                return emptyList()
            }

            override fun getAllEntriesFlow(): Flow<List<JournalEntry>> {
                return flowOf(listOfNotNull(savedEntry))
            }
        }

        val repository = PaymentRepository(
            api = fakeApi,
            journalDao = fakeDao,
            applicationScope = appScope,
            defaultDispatcher = testDispatcher
        )

        // Act - Simulate UI paying bill
        val transactionId = UUID.randomUUID().toString()
        repository.payBill(
            amountMinorUnits = 5000,
            customerId = "CUST-001",
            existingTransactionId = transactionId
        )

        // UI observes for 8 seconds, then gives up
        advanceTimeBy(8000)
        
        // Assert intent is journalled immediately
        assertEquals("PENDING", savedEntry?.status)
        assertEquals(true, apiCalled)

        // After 8 seconds, it is still pending because the network takes 15 seconds
        assertEquals("PENDING", statusUpdatedTo.ifEmpty { "PENDING" })

        // Now advance time past the network delay (15 seconds total)
        advanceTimeBy(7001)

        // Assert that the network call completed and updated the status to SUCCESS
        assertEquals("SUCCESS", statusUpdatedTo)
        assertEquals("SUCCESS", savedEntry?.status)
    }
}
