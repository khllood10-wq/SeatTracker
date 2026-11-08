package com.example.saudiaseattracker

import com.google.gson.annotations.SerializedName

// ═══════════ البحث ═══════════

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

// ═══════════ المسافرون → الدفع ═══════════

data class ElmVerificationRequest(
    @SerializedName("documentType") val documentType: String = "I",
    @SerializedName("documentNumber") val documentNumber: String,
    @SerializedName("nationality") val nationality: String = "SA",
    @SerializedName("dateOfBirth") val dateOfBirth: String
)

data class CartExtensionRequest(
    @SerializedName("content") val content: String,
    @SerializedName("travelerId") val travelerId: String,
    @SerializedName("extensionType") val extensionType: String = "TextExtension",
    @SerializedName("name") val name: String = "YQENSTATUS",
    @SerializedName("id") val id: String = "EXN-1-EXN"
)

data class EncryptedTravelerRequest(
    @SerializedName("key") val key: String,
    @SerializedName("body") val body: String
)

data class AddContactRequest(
    @SerializedName("address") val address: String? = null,
    @SerializedName("category") val category: String = "personal",
    @SerializedName("contactType") val contactType: String,
    @SerializedName("purpose") val purpose: String = "notification",
    @SerializedName("lang") val lang: String? = null,
    @SerializedName("travelerIds") val travelerIds: List<String>? = null,
    @SerializedName("countryPhoneExtension") val countryPhoneExtension: String? = null,
    @SerializedName("number") val number: String? = null,
    @SerializedName("deviceType") val deviceType: String? = null
)

data class CreateOrderRequest(
    @SerializedName("key") val key: String,
    @SerializedName("body") val body: String
)

data class GacaMandateRequest(
    @SerializedName("bounds") val bounds: List<GacaBound>
)

data class GacaBound(
    @SerializedName("originCountryCode") val originCountryCode: String,
    @SerializedName("destinationCountryCode") val destinationCountryCode: String,
    @SerializedName("stopOverCountryCodes") val stopOverCountryCodes: List<String> = emptyList()
)

data class PaymentMethodsRequest(
    @SerializedName("orderId") val orderId: String,
    @SerializedName("paymentRequests") val paymentRequests: List<PaymentRequest>
)

data class PaymentRequest(
    @SerializedName("paymentMethod") val paymentMethod: PaymentMethod,
    @SerializedName("authorization") val authorization: PaymentAuthorization
)

data class PaymentMethod(
    @SerializedName("paymentType") val paymentType: String = "CheckoutFormPayment"
)

data class PaymentAuthorization(
    @SerializedName("paymentRedirectionContext") val paymentRedirectionContext: PaymentRedirectionContext
)

data class PaymentRedirectionContext(
    @SerializedName("links") val links: List<PaymentLink>
)

data class PaymentLink(
    @SerializedName("rel") val rel: String,
    @SerializedName("href") val href: String
)
