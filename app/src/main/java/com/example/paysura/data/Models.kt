package com.example.paysura.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PayBillRequest(
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("agent_id") val agentId: String,
    val biller: String,
    @SerialName("customer_id") val customerId: String,
    @Serializable(with = BigDecimalSerializer::class) val amount: java.math.BigDecimal,
    val currency: String
)

@Serializable
data class TransactionStatusRequest(
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("agent_id") val agentId: String
)

@Serializable
data class PaymentResponseEnvelope(
    val message: PaymentResponseData? = null,
    @SerialName("exc_type") val excType: String? = null,
    val error: String? = null,
    @Serializable(with = BigDecimalSerializer::class) val required: java.math.BigDecimal? = null,
    @Serializable(with = BigDecimalSerializer::class) val available: java.math.BigDecimal? = null,
    val currency: String? = null
)

@Serializable
data class PaymentResponseData(
    val status: String,
    @SerialName("transaction_id") val transactionId: String,
    val currency: String,
    @SerialName("journal_entry") val journalEntry: String? = null,
    @SerialName("provider_reference") val providerReference: String? = null,
    @Serializable(with = BigDecimalSerializer::class) @SerialName("provider_cost") val providerCost: java.math.BigDecimal? = null,
    @Serializable(with = BigDecimalSerializer::class) @SerialName("agent_commission") val agentCommission: java.math.BigDecimal? = null,
    @Serializable(with = BigDecimalSerializer::class) @SerialName("remaining_balance") val remainingBalance: java.math.BigDecimal? = null,
    @SerialName("vend_data") val vendData: JsonElement? = null
)
