package com.kawler.parrallelmatrix.models

import kotlinx.serialization.Serializable

@Serializable
data class ServerData @JvmOverloads constructor(
    val calculationId: String = "",
    val calculation: CalculationData = CalculationData()
)
