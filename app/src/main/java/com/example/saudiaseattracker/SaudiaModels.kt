package com.example.saudiaseattracker

import com.google.gson.annotations.SerializedName

// ═══════════════ REQUEST MODELS ═══════════════

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
    @SerializedName("commercialFareFamilies") val commercialFareFamilies: List<String> =
        listOf("NCFFGUESTN", "NCFFBUSN", "NCFFFIRSTN"),
    @SerializedName("searchPreferences") val searchPreferences: SearchPreferences
)

data class Itinerary(
    @SerializedName("originLocationCode") val originLocationCode: String,
    @SerializedName("destinationLocationCode") val destinationLocationCode: String,
    @SerializedName("departureDateTime") val departureDateTime: String,
    @SerializedName("directFlights") val directFlights: Boolean = false,
    @SerializedName("isRequestedBound") val isRequestedBound: Boolean = true
)

data class Traveler(@SerializedName("passengerTypeCode") val passengerTypeCode: String = "ADT")

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

// ═══════════════ GACA MANDATE MODELS ═══════════════

data class GacaMandateRequest(
    @SerializedName("bounds") val bounds: List<GacaBound> = listOf(GacaBound())
)

data class GacaBound(
    @SerializedName("originCountryCode") val originCountryCode: String = "SA",
    @SerializedName("destinationCountryCode") val destinationCountryCode: String = "SA",
    @SerializedName("stopOverCountryCodes") val stopOverCountryCodes: List<String> = emptyList()
)

// ═══════════════ PASSENGER CONFIRMATION MODELS ═══════════════

data class ElmVerifyRequest(
    @SerializedName("documentType") val documentType: String = "I",
    @SerializedName("documentNumber") val documentNumber: String,
    @SerializedName("nationality") val nationality: String = "SA",
    @SerializedName("dateOfBirth") val dateOfBirth: String
)

data class CartExtensionRequest(
    @SerializedName("content") val content: String,
    @SerializedName("travelerId") val travelerId: String,
    @SerializedName("extensionType") val extensionType: String = "TextExtension",
    @SerializedName("name") val name: String = "YQENSTATUS"
)

// 🔥 التعديل الجديد: givenName + surname + documents array
data class TravelerUpdateRequest(
    @SerializedName("passengerTypeCode") val passengerTypeCode: String = "ADT",
    @SerializedName("givenName") val givenName: String,
    @SerializedName("surname") val surname: String,
    @SerializedName("gender") val gender: String = "male",
    @SerializedName("dateOfBirth") val dateOfBirth: String,
    @SerializedName("nationality") val nationality: String = "SA",
    @SerializedName("documents") val documents: List<TravelerDocument>
)

data class TravelerDocument(
    @SerializedName("type") val type: String = "NATIONAL_ID",
    @SerializedName("number") val number: String,
    @SerializedName("countryOfIssuance") val countryOfIssuance: String = "SA"
)

// ═══════════════ RESPONSE MODELS ═══════════════

data class SaudiaResponse(
    @SerializedName("data") val data: SaudiaData?
)

data class SaudiaData(
    @SerializedName("airBoundGroups") val airBoundGroups: List<AirBoundGroup>?
)

data class AirBoundGroup(
    @SerializedName("boundDetails") val boundDetails: BoundDetails?,
    @SerializedName("airBounds") val airBounds: List<AirBound>?
)

data class BoundDetails(
    @SerializedName("originLocationCode") val origin: String?,
    @SerializedName("destinationLocationCode") val destination: String?,
    @SerializedName("segments") val segments: List<Segment>?
)

data class Segment(
    @SerializedName("flightId") val flightId: String?
)

data class AirBound(
    @SerializedName("airBoundId") val airBoundId: String?,
    @SerializedName("fareFamilyCode") val fareFamilyCode: String?,
    @SerializedName("status") val status: FlightStatus?,
    @SerializedName("airOffer") val airOffer: AirOffer?
)

data class FlightStatus(
    @SerializedName("value") val value: String?
)

data class AirOffer(
    @SerializedName("totalPrice") val totalPrice: TotalPrice?
)

data class TotalPrice(
    @SerializedName("value") val value: Double?,
    @SerializedName("currencyCode") val currencyCode: String?
)
