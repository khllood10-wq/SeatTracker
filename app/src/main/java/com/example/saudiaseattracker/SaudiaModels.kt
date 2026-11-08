package com.example.saudiaseattracker

import com.google.gson.annotations.SerializedName

data class SaudiaSearchRequest(
    @SerializedName("region") val region: String = "KSA",
    @SerializedName("fromRegion") val fromRegion: String = "KSA",
    @SerializedName("toRegion") val toRegion: String = "KSA",
    @SerializedName("airBoundsSearchInputs") val airBoundsSearchInputs: AirBoundsSearchInputs,
    @SerializedName("offerCodeAvailabilityInput") val offerCodeAvailabilityInput: OfferCodeAvailabilityInput
)

data class AirBoundsSearchInputs(
    @SerializedName("itineraries") val itineraries: List<Itinerary>,
    @SerializedName("travelers") val travelers: List<Traveler>,
    @SerializedName("commercialFareFamilies") val commercialFareFamilies: List<String> = listOf("NCFFGUESTN", "NCFFBUSN", "NCFFFIRSTN"),
    @SerializedName("searchPreferences") val searchPreferences: SearchPreferences
)

data class Itinerary(
    @SerializedName("originLocationCode") val originLocationCode: String,
    @SerializedName("destinationLocationCode") val destinationLocationCode: String,
    @SerializedName("departureDateTime") val departureDateTime: String,
    @SerializedName("directFlights") val directFlights: Boolean = false,
    @SerializedName("isRequestedBound") val isRequestedBound: Boolean = true
)

data class Traveler(
    @SerializedName("passengerTypeCode") val passengerTypeCode: String = "ADT"
)

data class SearchPreferences(
    @SerializedName("showUnavailableEntries") val showUnavailableEntries: Boolean = false,
    @SerializedName("showMilesPrice") val showMilesPrice: Boolean = false,
    @SerializedName("showSoldOut") val showSoldOut: Boolean = true
)

data class OfferCodeAvailabilityInput(
    @SerializedName("channel") val channel: String = "MOBILE_WEB",
    @SerializedName("pointOfSale") val pointOfSale: String = "SA",
    @SerializedName("currencyCode") val currencyCode: String = "SAR",
    @SerializedName("language") val language: String = "AR",
    @SerializedName("browserId") val browserId: String = "db9387ab-b6f2-4d1a-9a08-0a55ac7a89d9"
)
