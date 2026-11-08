package com.example.saudiaseattracker

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@Composable
fun PaymentScreen(navController: NavController) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("صفحة الدفع", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("إجمالي المبلغ: 450 ريال", style = MaterialTheme.typography.titleLarge)
                Text("طريقة الدفع: سداد (SADAD)")
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { 
                // هنا لاحقاً نضع كود تأكيد الدفع
                navController.popBackStack(Screen.Search.route, inclusive = false) 
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("تأكيد الدفع")
        }
    }
}
