package com.example.paysura.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface PaymentApi {
    @POST("/api/method/paysura.api.accounting.bills.pay_bill")
    suspend fun payBill(@Body request: PayBillRequest): Response<PaymentResponseEnvelope>

    @POST("/api/method/paysura.api.accounting.bills.get_transaction_status")
    suspend fun getTransactionStatus(@Body request: TransactionStatusRequest): Response<PaymentResponseEnvelope>
}
