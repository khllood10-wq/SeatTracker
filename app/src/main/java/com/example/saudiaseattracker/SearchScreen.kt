package com.example.saudiaseattracker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    navController: NavController,
    viewModel: FlightViewModel
) {
    var flightNumber by remember { mutableStateOf("") }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val searchLogs by viewModel.searchLogs.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("البحث عن رحلة", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        // عرض إعدادات البحث الحالية
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("إعدادات الرحلة الحالية:", fontWeight = FontWeight.Bold)
                Text("من: ${viewModel.searchOrigin}  ✈️  إلى: ${viewModel.searchDestination}")
                Text("التاريخ: ${viewModel.searchDate}")
                Text("النوع: ذهاب فقط")
            }
        }

        OutlinedTextField(
            value = flightNumber,
            onValueChange = { flightNumber = it },
            label = { Text("رقم الرحلة (مثال: SV8844)") },
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState != SearchState.SEARCHING
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        if (uiState != SearchState.SEARCHING) {
            Button(
                onClick = { viewModel.startSearchingFlight(flightNumber) },
                modifier = Modifier.fillMaxWidth(),
                enabled = flightNumber.isNotBlank()
            ) {
                Text("بدء البحث التلقائي")
            }
        } else {
            Button(
                onClick = { viewModel.stopSearch() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("إيقاف البحث")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("سجل الاتصال:", style = MaterialTheme.typography.titleMedium)
            Button(onClick = {
                val fullLog = searchLogs.joinToString(separator = "\n")
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Logs", fullLog))
                Toast.makeText(context, "تم النسخ!", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "نسخ")
                Text(" نسخ")
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))

        SelectionContainer {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(searchLogs) { log ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text(
                            text = log, 
                            modifier = Modifier.padding(12.dp), 
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
