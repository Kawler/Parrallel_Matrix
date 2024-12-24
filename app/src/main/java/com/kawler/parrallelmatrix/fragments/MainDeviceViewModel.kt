package com.kawler.parrallelmatrix.fragments

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.kawler.parrallelmatrix.models.CalculationData
import com.kawler.parrallelmatrix.models.CalculationPart
import com.kawler.parrallelmatrix.models.CalculationResult
import com.kawler.parrallelmatrix.models.MatrixSize
import com.kawler.parrallelmatrix.models.SecondaryDeviceData
import com.kawler.parrallelmatrix.models.ServerData
import com.kawler.parrallelmatrix.models.ValueRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

class MainDeviceViewModel : ViewModel() {

    private val dbRef: DatabaseReference =
        FirebaseDatabase.getInstance("https://parrallel-matrix-default-rtdb.europe-west1.firebasedatabase.app/").reference
    val progress = MutableLiveData<Int>(0)
    val result = MutableLiveData<String>("")
    val calculationInProgress = MutableLiveData<Boolean>(false)
    private var calculationId: String = ""
    private var devicesConnected = hashMapOf<String, SecondaryDeviceData>()
    private val _deviceDiscoveryTimeout = MutableLiveData(false)
    private val deviceDiscoveryTimeout: LiveData<Boolean> = _deviceDiscoveryTimeout
    private var deviceListener: ValueEventListener? = null
    private var matrix: List<List<Double>> = listOf()

    fun startCalculation(
        rows: Int,
        cols: Int,
        minVal: Int,
        maxVal: Int
    ) {
        calculationInProgress.postValue(true)
        viewModelScope.launch {
            try {
                Log.d("MainDeviceViewModel", "startCalculation: Starting calculation...")
                calculationId = dbRef.push().key!!
                Log.d("MainDeviceViewModel", "startCalculation: Generated calculationId: $calculationId")
                generateMatrix(rows, cols, minVal, maxVal)
                val serverData = createServerData(rows, cols, minVal, maxVal, matrix)
                sendDataToServer(serverData)
                startDeviceDiscoveryTimeout()
                listenForDevices()
            } catch (e: Exception) {
                Log.e("MainDeviceViewModel", "Error starting calculation", e)
                result.postValue("Error during calculation: ${e.message}")
                calculationInProgress.postValue(false)
            }
        }
    }


    private fun startDeviceDiscoveryTimeout() {
        viewModelScope.launch {
            delay(10000)
            _deviceDiscoveryTimeout.postValue(true)
        }
    }

    private fun listenForDevices() {
        deviceListener =
            dbRef.child("calculations").child(calculationId).child("calculation").child("secondaryDevices")
                .addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        devicesConnected.clear()
                        for (deviceSnapshot in snapshot.children) {
                            val device = deviceSnapshot.getValue(SecondaryDeviceData::class.java)
                            val key = deviceSnapshot.key
                            if (device != null && key != null) {
                                devicesConnected[key] = device
                            }
                        }
                        Log.d("MainDeviceViewModel", "listenForDevices: Devices updated ${devicesConnected.size}")
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e("MainDeviceViewModel", "Device listener cancelled", error.toException())
                    }
                })

        viewModelScope.launch{
            startCalculationsIfTimeout() // observe livedata and start calculation when timeout occurs
        }
    }

    private suspend fun startCalculationsIfTimeout(){
        deviceDiscoveryTimeout.observeForever { timeout ->
            if (timeout){
                Log.d("MainDeviceViewModel", "startCalculationsIfTimeout: Timeout detected")
                deviceListener?.let {
                    dbRef.child("calculations").child(calculationId).child("calculation")
                        .child("secondaryDevices").removeEventListener(it)
                }
                viewModelScope.launch{ // using a coroutineScope to call suspend function
                    startCalculations(matrix) // start calculation
                }

                _deviceDiscoveryTimeout.removeObserver {  } // removing observer to prevent multiple calls
            }
        }
    }


    private fun generateMatrix(rows: Int, cols: Int, minVal: Int, maxVal: Int) {
        matrix = List(rows) {
            List(cols) {
                Random.nextDouble(minVal.toDouble(), maxVal.toDouble())
            }
        }
    }

    private suspend fun startCalculations(matrix: List<List<Double>>) {

        try {
            Log.d("MainDeviceViewModel", "startCalculations: Starting calculations...")
            val calculationData = assignCalculationParts(matrix)
            val serverData = ServerData(
                calculationId = calculationId,
                calculation = calculationData
            )
            sendDataToServer(serverData)
            calculateAndSendPartialSum(matrix, serverData)
            listenForCompletion()
        } catch (e: Exception) {
            Log.e("MainDeviceViewModel", "Error starting calculations on devices", e)
            result.postValue("Error during calculation: ${e.message}")
            calculationInProgress.postValue(false)
        }

    }

    private fun assignCalculationParts(matrix: List<List<Double>>): CalculationData {
        Log.d("MainDeviceViewModel", "assignCalculationParts: Assigning calculation parts...")
        val rows = matrix.size
        var startRow = 0
        var remainingRows = rows
        val totalDevices = devicesConnected.size + 1
        val rowsPerDevice = rows / totalDevices
        val secondaryDevicesWithParts = hashMapOf<String, SecondaryDeviceData>()

        devicesConnected.forEach { (key, device) ->
            val endRow = startRow + rowsPerDevice
            val currentEnd = if (remainingRows > 0) endRow + 1 else endRow
            val deviceWithPart = device.copy(
                calculationPart = CalculationPart(rowStart = startRow, rowEnd = currentEnd),
                status = "waiting"
            )

            secondaryDevicesWithParts[key] = deviceWithPart
            startRow = currentEnd
            remainingRows--
        }
        val endRow = startRow + rowsPerDevice + if (remainingRows > 0) 1 else 0
        val mainDevicePart = SecondaryDeviceData(
            deviceId = "main_device_id",
            status = "waiting",
            calculationPart = CalculationPart(rowStart = startRow, rowEnd = endRow),
            result = CalculationResult(0.0)
        )

        secondaryDevicesWithParts["main_device_id"] = mainDevicePart


        return CalculationData(
            mainDeviceId = "main_device_id",
            matrixSize = MatrixSize(matrix.size, matrix[0].size),
            valueRange = ValueRange(0, 1),
            secondaryDevices = secondaryDevicesWithParts,
            matrix = matrix,
            status = "in_progress"
        )
    }


    private fun createServerData(
        rows: Int,
        cols: Int,
        minVal: Int,
        maxVal: Int,
        matrix: List<List<Double>>
    ): ServerData {
        Log.d("MainDeviceViewModel", "createServerData: Creating ServerData object")
        return ServerData(
            calculationId = calculationId,
            calculation = CalculationData(
                mainDeviceId = "main_device_id",
                matrixSize = MatrixSize(rows, cols),
                valueRange = ValueRange(minVal, maxVal),
                secondaryDevices = hashMapOf(),
                matrix = matrix,
                status = "in_progress"
            )
        )
    }


    private fun calculateAndSendPartialSum(matrix: List<List<Double>>, serverData: ServerData) {
        Log.d("MainDeviceViewModel", "calculateAndSendPartialSum: calculating result for main device...")
        val calculationData = serverData.calculation
        var sum = 0.0
        val mainDevice = calculationData.secondaryDevices["main_device_id"]
        val startRow = mainDevice?.calculationPart!!
        for (i in startRow.rowStart..startRow.rowEnd) {
            if (i < matrix.size) {
                for (j in matrix[i].indices) {
                    sum += matrix[i][j]
                }
            }
        }


        val result = CalculationResult(sum = sum)
        viewModelScope.launch {
            val deviceRef =
                dbRef.child("calculations").child(calculationId).child("calculation").child("secondaryDevices")
            val query = deviceRef.orderByChild("deviceId").equalTo("main_device_id")
            query.get().addOnSuccessListener { snapshot ->
                if (snapshot.children.iterator().hasNext()) {
                    val key = snapshot.children.iterator().next().key
                    val updateStatus = hashMapOf<String, Any>(
                        "status" to "completed",
                        "result" to result
                    )
                    viewModelScope.launch {
                        deviceRef.child(key!!).updateChildren(updateStatus).await()
                    }
                } else {
                    Log.e("MainDeviceViewModel", "No device data on server for id main_device_id")
                }
            }.addOnFailureListener {
                Log.e("MainDeviceViewModel", "Error getting device data on server", it)
            }
        }

    }

    private suspend fun sendDataToServer(serverData: ServerData) {
        Log.d("MainDeviceViewModel", "sendDataToServer: Sending data to server with calculationId: $calculationId")
        try {
            dbRef.child("calculations").child(calculationId).setValue(serverData).await()
            Log.d("MainDeviceViewModel", "sendDataToServer: Data sent to server")
        } catch (e: Exception) {
            Log.e("MainDeviceViewModel", "sendDataToServer: Error sending data to server", e)
            result.postValue("Error sending data to server: ${e.message}")
            calculationInProgress.postValue(false)
        }

    }

    private fun listenForCompletion() {
        Log.d("MainDeviceViewModel", "listenForCompletion: Listening for completion...")
        dbRef.child("calculations").child(calculationId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) {
                        Log.e("MainDeviceViewModel", "Calculation data does not exists on server!")
                        return
                    }
                    val serverData = snapshot.getValue(ServerData::class.java) ?: return
                    val updatedCalculationData = serverData.calculation

                    var completedDevices = 0
                    var sum = 0.0
                    updatedCalculationData.secondaryDevices.forEach { (_, value) ->
                        if (value.status == "completed") {
                            sum += value.result?.sum ?: 0.0
                            completedDevices++
                        }
                    }
                    val numberOfDevices = updatedCalculationData.secondaryDevices.size
                    val progressValue = (completedDevices.toDouble() / numberOfDevices * 100).toInt()
                    progress.postValue(progressValue)
                    Log.d("MainDeviceViewModel", "Progress: $progressValue")
                    if (completedDevices == numberOfDevices) {
                        onAllDevicesFinished(sum)
                        dbRef.child("calculations").child(calculationId).removeEventListener(this)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("MainDeviceViewModel", "Listener cancelled", error.toException())
                    result.postValue("Error during calculation: ${error.message}")
                    calculationInProgress.postValue(false)
                }
            })
    }


    private fun onAllDevicesFinished(sum: Double) {
        val resultText = "Final Sum of the Matrix: $sum"
        result.postValue(resultText)
        calculationInProgress.postValue(false)
        Log.d("MainDeviceViewModel", "Calculation finished. Total sum: $sum")
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            if (calculationId.isNotEmpty()) {
                try {
                    dbRef.child("calculations").child(calculationId).removeValue().await()
                } catch (e: Exception) {
                    Log.e("MainDeviceViewModel", "Error deleting data from server", e)
                }
            }
        }
    }
}