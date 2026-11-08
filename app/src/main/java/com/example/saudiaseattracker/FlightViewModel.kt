package com.example.saudiaseattracker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class FlightViewModel : ViewModel() {
    // حالة البحث
    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    // سجل البحث
    private val _searchLogs = MutableStateFlow<List<String>>(emptyList())
    val searchLogs: StateFlow<List<String>> = _searchLogs

    // حالة توفر الرحلة (مبدئياً هنحاكيها بالكود)
    private val _isFlightAvailable = MutableStateFlow(false)
    val isFlightAvailable: StateFlow<Boolean> = _isFlightAvailable

    fun startSearchingFlight(flightNumber: String, onFlightFound: () -> Unit) {
        if (_isSearching.value) return
        
        _isSearching.value = true
        _isFlightAvailable.value = false
        
        viewModelScope.launch {
            var attempts = 0
            // محاكاة البحث المستمر (أقصى حد 10 محاولات للتجربة)
            while (attempts < 10) {
                addLog("محاولة $attempts: جاري فحص الرحلة $flightNumber...")
                delay(3000) // انتظار 3 ثواني بين كل محاولة
                
                // هنا لاحقاً راح نضع طلب الـ API الحقيقي
                // حالياً هنحاكي إن الرحلة توفرت في المحاولة الخامسة
                if (attempts == 4) { 
                    addLog("✅ تم العثور على مقعد في الرحلة $flightNumber!")
                    _isFlightAvailable.value = true
                    _isSearching.value = false
                    onFlightFound()
                    return@launch
                }
                
                addLog("❌ الرحلة ممتلئة، إعادة المحاولة...")
                attempts++
            }
            addLog("⚠️ توقف البحث بعد 10 محاولات بدون نتيجة.")
            _isSearching.value = false
        }
    }

    private fun addLog(message: String) {
        val currentLogs = _searchLogs.value.toMutableList()
        currentLogs.add(0, message) // إضافة السجل في الأعلى
        _searchLogs.value = currentLogs
    }
}
