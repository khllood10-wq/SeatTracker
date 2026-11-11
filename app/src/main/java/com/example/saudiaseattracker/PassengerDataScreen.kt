package com.example.saudiaseattracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassengerDataScreen(navController: NavController, viewModel: FlightViewModel) {
    val p = viewModel.passengerData
    var title by remember { mutableStateOf(p.title) }
    var firstName by remember { mutableStateOf(p.firstName) }
    var lastName by remember { mutableStateOf(p.lastName) }
    var dob by remember { mutableStateOf(p.dateOfBirth) }
    var nationalId by remember { mutableStateOf(p.nationalId) }
    var email by remember { mutableStateOf(p.email) }
    var phone by remember { mutableStateOf(p.phone) }

    var titleExpanded by remember { mutableStateOf(false) }
    val titleOptions = listOf("Mr", "Mrs", "Ms")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("👤 بيانات المسافر", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // Title dropdown
        ExposedDropdownMenuBox(
            expanded = titleExpanded,
            onExpandedChange = { titleExpanded = it }
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = {},
                readOnly = true,
                label = { Text("اللقب") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = titleExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = titleExpanded,
                onDismissRequest = { titleExpanded = false }
            ) {
                titleOptions.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(opt) },
                        onClick = {
                            title = opt
                            titleExpanded = false
                        }
                    )
                }
            }
        }

        OutlinedTextField(
            value = firstName,
            onValueChange = { firstName = it },
            label = { Text("الاسم الأول") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = lastName,
            onValueChange = { lastName = it },
            label = { Text("اسم العائلة") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = dob,
            onValueChange = { dob = it },
            label = { Text("تاريخ الميلاد (YYYY-MM-DD)") },
            placeholder = { Text("1990-02-18") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = nationalId,
            onValueChange = { if (it.length <= 10) nationalId = it },
            label = { Text("رقم الهوية (10 أرقام)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("البريد الإلكتروني") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email)
        )

        OutlinedTextField(
            value = phone,
            onValueChange = { if (it.length <= 10) phone = it },
            label = { Text("رقم الجوال (بدون 966)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Phone)
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                viewModel.passengerData = PassengerData(
                    title = title,
                    firstName = firstName,
                    lastName = lastName,
                    dateOfBirth = dob,
                    nationalId = nationalId,
                    email = email,
                    phone = phone
                )
                viewModel.addLog("💾 تم حفظ بيانات المسافر")
                navController.navigate(Screen.Home.route) {
                    popUpTo(Screen.Home.route) { inclusive = true }
                }
            },
            enabled = firstName.isNotBlank() && lastName.isNotBlank() &&
                    dob.isNotBlank() && nationalId.length == 10 &&
                    email.contains("@") && phone.length >= 9,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("💾 حفظ والعودة", fontWeight = FontWeight.Bold)
        }

        Text(
            "هذه البيانات تُستخدم في تأكيد المسافر وتذكرة الحجز.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
