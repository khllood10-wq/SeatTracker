package com.example.saudiaseattracker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// حالات البحث
enum class SearchState {
    IDLE,
    SEARCHING,
    FOUND,
    ERROR
}

class FlightViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SearchState.IDLE)
    val uiState: StateFlow<SearchState> = _uiState

    private val _searchLogs = MutableStateFlow<List<String>>(emptyList())
    val searchLogs: StateFlow<List<String>> = _searchLogs

    fun startSearchingFlight(flightNumber: String) {
        if (_uiState.value == SearchState.SEARCHING) return
        
        _uiState.value = SearchState.SEARCHING
        _searchLogs.value = emptyList() // مسح السجل السابق
        addLog("بدء البحث عن الرحلة: $flightNumber")
        
        viewModelScope.launch {
            var attempts = 1
            val maxAttempts = 15 // حد أقصى لتجنب التعليق للأبد
            
            while (attempts <= maxAttempts && _uiState.value == SearchState.SEARCHING) {
                addLog("المحاولة $attempts: جاري الاتصال بالسيرفر لفحص المقاعد...")
                
                // هنا لاحقاً نضع الدالة التي تستدعي الـ API الحقيقي
                val isAvailable = mockApiCall(attempts)
                
                if (isAvailable) {
                    addLog("✅ تم العثور على مقعد! جاري تحويلك لصفحة المسافرين...")
                    _uiState.value = SearchState.FOUND
                    return@launch
                } else {
                    addLog("❌ الرحلة ممتلئة أو غير متاحة. انتظار 5 ثوانٍ...")
                }
                
                delay(5000) // انتظار 5 ثواني قبل المحاولة القادمة
                attempts++
            }
            
            if (_uiState.value == SearchState.SEARCHING) {
                addLog("⚠️ توقف البحث: لم يتم العثور على مقعد بعد $maxAttempts محاولة.")
                _uiState.value = SearchState.ERROR
            }
        }
    }

    fun stopSearch() {
        _uiState.value = SearchState.IDLE
        addLog("تم إيقاف البحث يدوياً.")
    }

    // دالة وهمية تحاكي استجابة الـ API (ترجع true إذا توفر مقعد)
    private suspend fun mockApiCall(attempt: Int): Boolean {
        delay(1500) // محاكاة وقت الشبكة
        // نجعلها ترجع true في المحاولة رقم 4 للتجربة
        return attempt == 4 
    }

    private fun addLog(message: String) {
        val currentLogs = _searchLogs.value.toMutableList()
        // إضافة الوقت التقريبي للمحاولة (اختياري)
        currentLogs.add(0, message) 
        _searchLogs.value = currentLogs
    }
}
