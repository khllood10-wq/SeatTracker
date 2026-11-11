package com.example.saudiaseattracker

sealed class Screen(val route: String, val title: String) {
    object Home             : Screen("home", "الرئيسية")
    object Passenger        : Screen("passenger", "بيانات المسافر")
    object Flight           : Screen("flight", "بيانات الرحلة")
    object TokenChallenge   : Screen("token_challenge", "تحدي التوكن")
    object TokenInitTester  : Screen("token_init", "اختبار إعادة التوكن")
}
