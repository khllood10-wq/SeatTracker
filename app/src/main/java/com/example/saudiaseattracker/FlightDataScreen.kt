package com.example.saudiaseattracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightDataScreen(navController: NavController, viewModel: FlightViewModel) {
    val f = viewModel.flightData
    var from by remember { mutableStateOf(f.from) }
    var to by remember { mutableStateOf(f.to) }
    var date by remember { mutableStateOf(f.date) }
    var departureTime by remember { mutableStateOf(f.departureTime) }
    var cabinClass by remember { mutableStateOf(f.cabinClass) }
    var directOnly by remember { mutableStateOf(f.directOnly) }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("✈️ بيانات الرحلة", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = from,
            onValueChange = { if (it.length <= 3) from = it.uppercase() },
            label = { Text("من (JED)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = to,
            onValueChange = { if (it.length <= 3) to = it.uppercase() },
            label = { Text("إلى (AHB)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = date,
            onValueChange = { date = it },
            label = { Text("التاريخ (YYYY-MM-DD)") },
            modifier = Modifier.fillMaxWidth()
        )

        // ═══ وقت الإقلاع (بدل رقم الرحلة) ═══
        OutlinedTextField(
            value = departureTime,
            onValueChange = { departureTime = it },
            label = { Text("وقت الإقلاع (HH:MM)") },
            placeholder = { Text("مثال: 06:40 أو 0640") },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "اتركه فارغاً لعرض جميع رحلات اليوم",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ═══ فئة السفر ═══
        CabinDropdown(
            selected = cabinClass,
            onSelect = { cabinClass = it }
        )

        // ═══ بدون توقف فقط ═══
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("✈️ بدون توقف فقط", fontWeight = FontWeight.Bold)
                    Text(
                        if (directOnly) "فقط الرحلات المباشرة" else "يشمل الرحلات المتوقفة",
                        fontSize = 11.sp
                    )
                }
                Switch(directOnly, { directOnly = it })
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                viewModel.flightData = FlightData(
                    from = from,
                    to = to,
                    date = date,
                    departureTime = departureTime,
                    cabinClass = cabinClass,
                    directOnly = directOnly
                )
                navController.popBackStack()
            },
            enabled = from.length == 3 && to.length == 3 && date.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("💾 حفظ", fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CabinDropdown(
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = FareFamilies.displayName(selected)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("الفئة (Class)") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            FareFamilies.DISPLAY_OPTIONS.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(key)
                        expanded = false
                    },
                    trailingIcon = if (key == selected) {
                        { Icon(Icons.Default.Check, null) }
                    } else null
                )
            }
        }
    }
}
