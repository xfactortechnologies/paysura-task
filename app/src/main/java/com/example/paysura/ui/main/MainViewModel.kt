package com.example.paysura.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.paysura.data.JournalEntry
import com.example.paysura.data.PaymentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigDecimal

class MainViewModel(private val repository: PaymentRepository) : ViewModel() {

    val entries: StateFlow<List<JournalEntry>> = repository.getAllEntriesFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _paymentState = MutableStateFlow<PaymentState>(PaymentState.Idle)
    val paymentState = _paymentState.asStateFlow()

    fun pay(amountText: String, customerId: String) {
        val amount = try {
            BigDecimal(amountText).multiply(BigDecimal(100)).longValueExact()
        } catch (e: Exception) {
            null
        }

        if (amount == null || amount <= 0L || customerId.isBlank()) {
            _paymentState.value = PaymentState.Error("Invalid input")
            return
        }

        _paymentState.value = PaymentState.Paying
        viewModelScope.launch {
            val transactionId = repository.payBill(
                amountMinorUnits = amount,
                customerId = customerId
            )

            // The core requirement: The UI observes the request for at most 8 seconds.
            // After that it stops waiting and returns to an idle/ready state, with the transaction visible somewhere as unresolved.
            val result = withTimeoutOrNull(8000) {
                repository.getEntryFlow(transactionId).first { entry ->
                    entry != null && entry.status != "PENDING"
                }
            }

            if (result == null) {
                // Timeout! The network call in the repository continues, but UI gives up.
                _paymentState.value = PaymentState.Idle
            } else {
                // It finished within 8 seconds!
                _paymentState.value = PaymentState.Idle
            }
        }
    }

    fun resetState() {
        _paymentState.value = PaymentState.Idle
    }

    fun retry(entry: JournalEntry) {
        _paymentState.value = PaymentState.Paying
        viewModelScope.launch {
            val isTerminalNegative = entry.status == "DECLINED" || entry.status == "REVERSED"
            val transactionIdToUse = if (isTerminalNegative) null else entry.transactionId

            val transactionId = repository.payBill(
                amountMinorUnits = entry.amountMinorUnits,
                customerId = entry.customerId,
                agentId = entry.agentId,
                biller = entry.biller,
                currency = entry.currency,
                existingTransactionId = transactionIdToUse
            )

            val result = withTimeoutOrNull(8000) {
                repository.getEntryFlow(transactionId).first { e ->
                    e != null && e.status != "PENDING"
                }
            }

            _paymentState.value = PaymentState.Idle
        }
    }

    companion object {
        fun provideFactory(repository: PaymentRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MainViewModel(repository) as T
            }
        }
    }
}

sealed class PaymentState {
    object Idle : PaymentState()
    object Paying : PaymentState()
    data class Error(val message: String) : PaymentState()
}
