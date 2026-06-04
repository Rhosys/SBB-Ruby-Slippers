package ch.rhosys.sbb.domain.model

import java.math.BigDecimal

enum class FareProfile {
    FULL,
    HALF_FARE,
    GA,       // General Abonnement — journey costs nothing
    SEVEN25,  // 7-25 night discount
}

data class Fare(
    val fullPrice: BigDecimal,
    val adjustedPrice: BigDecimal,
    val currency: String = "CHF",
    val profile: FareProfile,
    val isCoveredByPass: Boolean = profile == FareProfile.GA,
)
