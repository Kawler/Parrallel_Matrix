package com.kawler.parrallelmatrix.models

import kotlinx.serialization.Serializable

@Serializable
data class CalculationData @JvmOverloads constructor(
    var status: String = "pending",
    val mainDeviceId: String = "",
    val matrixSize: MatrixSize = MatrixSize(0,0),
    val valueRange: ValueRange = ValueRange(0,0),
    var secondaryDevices: HashMap<String, SecondaryDeviceData> = hashMapOf(),
    val matrix: List<List<Double>> = listOf(),
    var result: CalculationResult? = null,
    val timestamp: String = java.time.Instant.now().toString()
)

@Serializable
data class MatrixSize @JvmOverloads constructor(
    val rows: Int = 0,
    val cols: Int = 0
)

@Serializable
data class ValueRange @JvmOverloads constructor(
    val min: Int = 0,
    val max: Int = 0
)

@Serializable
data class CalculationPart @JvmOverloads constructor(
    val rowStart: Int = 0,
    val rowEnd: Int = 0
)

@Serializable
data class SecondaryDeviceData @JvmOverloads constructor(
    var deviceId: String = "",
    var status: String = "waiting",
    var calculationPart: CalculationPart? = null,
    var result: CalculationResult? = null
)


@Serializable
data class CalculationResult @JvmOverloads constructor(
    val sum: Double = 0.0
)