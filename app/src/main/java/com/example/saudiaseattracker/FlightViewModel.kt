// ═══════════════════════════════════════════════════════════
//  🔍 البحث الذكي — عرض جميع الرحلات دائماً
// ═══════════════════════════════════════════════════════════
private suspend fun searchUntilFound(max: Int = 100): FoundFlightDetails? {
    var attempt = 0

    val wantedTime = FlightData.normalizeTime(flightData.departureTime)
    val wantedCodes = FareFamilies.FARE_CODE_MAP[flightData.cabinClass] ?: emptyList()

    addLog("🎯 بحث: وقت=${wantedTime.ifBlank { "أي" }} | فئة=${FareFamilies.displayName(flightData.cabinClass)} | مباشر=${flightData.directOnly}")

    while (attempt < max) {
        attempt++
        setStep("search", StepStatus.RUNNING, "محاولة $attempt/$max")
        _searchResponseText.value = "⏳ جاري إرسال الطلب... (محاولة $attempt)"

        try {
            val resp = RetrofitClient.api.searchAirBounds(
                headers = buildHeaders("4"),
                request = SaudiaSearchRequest(
                    airBoundsSearchInputs = AirBoundsSearchInputs(
                        itineraries = listOf(Itinerary(
                            originLocationCode = flightData.from,
                            destinationLocationCode = flightData.to,
                            departureDateTime = "${flightData.date}T00:00:00.000",
                            directFlights = flightData.directOnly
                        )),
                        travelers = listOf(Traveler()),
                        searchPreferences = SearchPreferences()
                    ),
                    offerCodeAvailabilityInput = OfferCodeAvailabilityInput()
                )
            )
            saveCookiesFromResponse(resp)

            if (resp.code() == 401) {
                _searchResponseText.value = "🚨 انتهت الجلسة (401)"
                jwtExpiryMs = 0L
                return null
            }
            if (resp.code() == 403) {
                _searchResponseText.value = "🚨 Imperva رفض (403)"
                return null
            }

            if (resp.isSuccessful) {
                val body = resp.body() ?: continue
                val flights = parseAirBounds(body)
                addLog("📡 استلمنا ${flights.size} رحلة")

                val match = findMatchingFlight(flights, wantedTime, wantedCodes)
                val matchedFlightNumber = match?.first?.flightNumber
                val matchedFareCode = match?.second?.code

                // ═══ عرض جميع الرحلات دائماً ═══
                _searchResponseText.value = buildString {
                    appendLine("📊 ${flights.size} رحلة | محاولة $attempt | HTTP ${resp.code()}")
                    appendLine("🎯 المطلوب: وقت=${wantedTime.ifBlank { "أي" }} | فئة=${wantedCodes.joinToString(",")} | مباشر=${if (flightData.directOnly) "✅" else "❌"}")
                    appendLine("────────────────────────")
                    appendLine()

                    flights.forEachIndexed { idx, f ->
                        val isMatched = f.flightNumber == matchedFlightNumber
                        val prefix = if (isMatched) "🎯" else "  "
                        val directStr = if (f.isDirect) "مباشر" else "${f.stops}st"

                        appendLine("$prefix ${idx + 1}. ${f.flightNumber} @ ${f.departTime} | $directStr")

                        // عرض جميع الفئات
                        f.fares.forEach { fare ->
                            val status = if (fare.isAvailable) "✅" else "❌"
                            val star = if (isMatched && fare.code == matchedFareCode) " ⬅ المختارة" else ""
                            appendLine("     $status ${fare.code} | ${fare.price} SAR | quota=${fare.quota}$star")
                        }
                        appendLine()
                    }

                    if (match != null) {
                        appendLine("────────────────────────")
                        appendLine("✅ تم العثور على مطابقة")
                    } else {
                        appendLine("────────────────────────")
                        appendLine("⚠️ لا مطابقة — سيُعاد المحاولة")
                    }
                }

                if (match != null) {
                    val (flight, fare) = match
                    addLog("🎯 مطابقة: ${flight.flightNumber} @ ${flight.departTime} | ${fare.code} | ${fare.price} SAR")
                    return FoundFlightDetails(
                        flightNumber = flight.flightNumber,
                        date = flightData.date,
                        origin = flight.origin,
                        destination = flight.destination,
                        price = fare.price,
                        fareFamily = fare.code,
                        airBoundId = fare.airBoundId
                    )
                } else {
                    addLog("⚠️ لا مطابقة — ${flights.size} رحلة تم فحصها")
                }
            } else {
                _searchResponseText.value = "⚠️ استجابة غير متوقعة: HTTP ${resp.code()}"
            }
        } catch (e: Exception) {
            _searchResponseText.value = "❌ خطأ في الاتصال:\n${e.message}"
        }
        if (attempt < max) delay(Random.nextLong(5_000L, 8_000L))
    }
    return null
}
