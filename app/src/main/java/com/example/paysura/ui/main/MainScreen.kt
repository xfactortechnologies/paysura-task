package com.example.paysura.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.paysura.data.JournalEntry
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

class ThousandsSeparatorVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val originalText = text.text
        if (originalText.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        
        val parts = originalText.split(".")
        val integerPart = parts[0]
        val fractionPart = if (parts.size > 1) "." + parts[1] else ""
        
        val formattedInteger = integerPart.reversed().chunked(3).joinToString(",").reversed()
        val formattedText = formattedInteger + fractionPart
        
        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val intPartLength = integerPart.length
                if (offset <= intPartLength) {
                    val commasBefore = max(0, (intPartLength - 1) / 3) - max(0, (intPartLength - offset - 1) / 3)
                    return offset + commasBefore
                } else {
                    val totalCommas = max(0, (intPartLength - 1) / 3)
                    return offset + totalCommas
                }
            }

            override fun transformedToOriginal(offset: Int): Int {
                var commas = 0
                for (i in 0 until offset) {
                    if (i < formattedText.length && formattedText[i] == ',') commas++
                }
                return offset - commas
            }
        }
        
        return TransformedText(AnnotatedString(formattedText), offsetMapping)
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val entries by viewModel.entries.collectAsState()
    val paymentState by viewModel.paymentState.collectAsState()

    var amount by remember { mutableStateOf("") }
    var customerId by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Text("Paysura POS", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = customerId,
            onValueChange = { customerId = it },
            label = { Text("Customer ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = paymentState !is PaymentState.Paying
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = amount,
            onValueChange = { 
                // Only allow numbers and decimal point
                if (it.matches(Regex("^\\d*\\.?\\d*$"))) {
                    amount = it
                }
            },
            label = { Text("Amount") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            visualTransformation = ThousandsSeparatorVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = paymentState !is PaymentState.Paying
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { 
                viewModel.pay(amount, customerId)
                amount = ""
                customerId = ""
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = paymentState !is PaymentState.Paying && amount.isNotBlank() && customerId.isNotBlank()
        ) {
            if (paymentState is PaymentState.Paying) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text("Pay")
            }
        }

        if (paymentState is PaymentState.Error) {
            Text(
                text = (paymentState as PaymentState.Error).message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("Transaction Journal", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(entries, key = { it.transactionId }) { entry ->
                JournalEntryRow(entry = entry, onRetry = { viewModel.retry(entry) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun JournalEntryRow(entry: JournalEntry, onRetry: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val time = dateFormat.format(Date(entry.createdAt))
    val amountDecimal = java.math.BigDecimal(entry.amountMinorUnits).divide(java.math.BigDecimal(100))
    val format = remember { NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    } }
    val amountStr = format.format(amountDecimal)
    
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("To: ${entry.customerId}", style = MaterialTheme.typography.bodyMedium)
            Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.status == "PENDING" || entry.status == "DECLINED" || entry.status == "REVERSED") {
                TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) {
                    Text("Retry")
                }
            }
        }
        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
            Text("Amount: $amountStr", style = MaterialTheme.typography.bodyLarge)
            val color = when (entry.status) {
                "SUCCESS" -> MaterialTheme.colorScheme.primary
                "FAILED", "DECLINED", "REVERSED" -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(entry.status, style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}
