// دوال حفظ واسترجاع التوكنات
fun saveTokens(auth: String, xDToken: String) {
    val prefs = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
    prefs.edit()
        .putString("auth_token", auth)
        .putString("xd_token", xDToken)
        .apply()
    addLog("✅ تم التقاط التوكن وتخزينه بنجاح!")
}

private fun getSavedAuthToken(): String? {
    val prefs = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
    return prefs.getString("auth_token", null)
}

private fun getSavedXDToken(): String? {
    val prefs = getApplication<Application>().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
    return prefs.getString("xd_token", null)
}
