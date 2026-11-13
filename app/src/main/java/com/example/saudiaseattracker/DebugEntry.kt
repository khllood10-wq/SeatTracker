package com.example.saudiaseattracker

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

data class DebugEntry(
    val id: Long = System.nanoTime(),
    val timestamp: Long = System.currentTimeMillis(),
    val source: DebugSource,
    val method: String = "",
    val url: String = "",
    val statusCode: Int? = null,
    val title: String = "",
    val headers: String = "",
    val body: String = ""
)

enum class DebugSource(val label: String, val colorHex: Long) {
    APP      ("APP",       0xFF607D8BL),
    HYPER    ("HYPER",     0xFFFF9800L),
    HTTPCLOAK("HTTPCLOAK", 0xFF2196F3L),
    RETROFIT ("RETROFIT",  0xFF9C27B0L),
    SEARCH   ("SEARCH",    0xFF4CAF50L)
}

/**
 * ناقل مركزي لكل أحداث التشخيص.
 * كل من: RetrofitClient، HyperSessionManager، FlightViewModel يبثّ هنا.
 * FlightViewModel يجمع ويحوّل إلى StateFlow للـ UI.
 */
object DebugBus {
    private val _flow = MutableSharedFlow<DebugEntry>(extraBufferCapacity = 1000)
    val flow: SharedFlow<DebugEntry> = _flow
    fun emit(entry: DebugEntry) { _flow.tryEmit(entry) }
}
