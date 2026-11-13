// داخل HttpCloakClient.kt

companion object {
    // UA لـ Windows Chrome/131 (متوافق مع Hyper)
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"
}

@Synchronized
private fun getSession(): Session {
    val now = System.currentTimeMillis()
    val existing = session
    if (existing == null || (now - sessionCreatedAt) > 30 * 60_000L) {
        try { existing?.close() } catch (_: Exception) {}
        val newS = Session(
            SessionConfig(
                // ✅ استخدام preset لـ Windows
                preset = "chrome-latest-windows",
                timeout = 30,
                httpVersion = "h2"
            )
        )
        session = newS
        sessionCreatedAt = now
        DebugBus.emit(DebugEntry(
            source = DebugSource.HTTPCLOAK,
            title = "🆕 Session جديدة (Windows)",
            body = "preset=chrome-latest-windows\ntimeout=30s\nhttpVersion=h2\nUA=$UA"
        ))
        return newS
    }
    return existing
}
