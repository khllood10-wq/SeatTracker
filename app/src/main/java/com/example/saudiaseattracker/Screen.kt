package com.example.saudiaseattracker

sealed class Screen(val route: String, val title: String) {
    object Login    : Screen("login", "تسجيل الدخول")
    object Home     : Screen("home", "الرئيسية")
    object Passenger: Screen("passenger", "بيانات المسافر")
    object Flight   : Screen("flight", "بيانات الرحلة")
}
