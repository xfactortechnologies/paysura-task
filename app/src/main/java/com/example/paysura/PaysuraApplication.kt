package com.example.paysura

import android.app.Application
import com.example.paysura.data.AppDatabase
import com.example.paysura.data.PaymentApi
import com.example.paysura.data.PaymentRepository
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

class PaysuraApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        
        // Start up resolver loop
        container.applicationScope.launch {
            while (true) {
                container.paymentRepository.resolvePendingTransactions()
                kotlinx.coroutines.delay(10000) // Poll every 10 seconds for robustness
            }
        }
    }
}

class AppContainer(application: Application) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val json = Json { ignoreUnknownKeys = true }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("http://localhost:4499") // Assume emulator for now
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    private val paymentApi = retrofit.create(PaymentApi::class.java)

    private val database = AppDatabase.getDatabase(application)

    val paymentRepository = PaymentRepository(
        api = paymentApi,
        journalDao = database.journalDao(),
        applicationScope = applicationScope
    )
}
