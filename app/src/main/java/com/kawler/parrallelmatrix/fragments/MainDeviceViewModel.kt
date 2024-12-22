package com.kawler.parrallelmatrix.fragments

import android.util.Log
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

class MainDeviceViewModel : ViewModel() {

    private val dbRef: DatabaseReference = FirebaseDatabase.getInstance("https://parrallel-matrix-default-rtdb.europe-west1.firebasedatabase.app/").reference
    val progress = MutableLiveData<Int>(0) // 0 to 100
    val result = MutableLiveData<String>("")
    val calculationInProgress = MutableLiveData<Boolean>(false)

    private var calculationId: String = ""

    // Function to start calculation
    fun startCalculation(
        rows: Int,
        cols: Int,
        minVal: Int,
        maxVal: Int,
        numberOfDevices: Int
    ) {
        calculationInProgress.postValue(true)
        viewModelScope.launch {
            try {
                val matrix = generateMatrix(rows, cols, minVal, maxVal)
                calculationId = dbRef.push().key!!

                val serverData = createServerData(rows, cols, minVal, maxVal, numberOfDevices, matrix)
                sendDataToServer(serverData)
                calculateAndSendPartialSum(matrix, serverData)
                listenForCompletion()
            } catch (e: Exception) {
                Log.e("MainDeviceViewModel", "Error starting calculation", e)
                result.postValue("Error during calculation: ${e.message}")
                calculationInProgress.postValue(false)
            }
        }
    }


    private fun generateMatrix(rows: Int, cols: Int, minVal: Int, maxVal: Int): List<List<Double>> {
        return List(rows) {
            List(cols) {
                Random.nextDouble(minVal.toDouble(), maxVal.toDouble())
            }
        }
    }


    private fun createServerData(
        rows: Int,
        cols: Int,
        minVal: Int,
        maxVal: Int,
        numberOfDevices: Int,
        matrix: List<List<Double>>
    ): ServerData {
        val secondaryDevices = generateSecondaryDevicesData(rows, numberOfDevices)

        return ServerData(
            calculationId = calculationId,
            calculation =  CalculationData(
                mainDeviceId = "main_device_id", //todo: should be device id
                matrixSize = MatrixSize(rows, cols),
                valueRange = ValueRange(minVal, maxVal),
                secondaryDevices = secondaryDevices,
                matrix = matrix,
                status = "in_progress"
            )
        )

    }

    private fun generateSecondaryDevicesData(rows: Int, numberOfDevices: Int): MutableList<SecondaryDeviceData> {
        val secondaryDevices = mutableListOf<SecondaryDeviceData>()
        val rowsPerDevice = rows / numberOfDevices
        var remainingRows = rows % numberOfDevices
        var startRow = 0

        for (i in 1..numberOfDevices) {
            val endRow = startRow + rowsPerDevice + if (remainingRows > 0) 1 else 0
            secondaryDevices.add(
                SecondaryDeviceData(
                    deviceId = "device_$i", // TODO: should be unique device id
                    status = "waiting",
                    calculationPart = CalculationPart(rowStart = startRow, rowEnd = endRow),
                    result = CalculationResult(0.0)
                )
            )
            startRow = endRow
            remainingRows--
        }
        return secondaryDevices
    }

    private fun calculateAndSendPartialSum(matrix: List<List<Double>>, serverData: ServerData){
        val calculationData = serverData.calculation
        var sum = 0.0
        val firstDevice = calculationData.secondaryDevices.firstOrNull()
        val startRow = firstDevice?.calculationPart!!

        for (i in startRow.rowStart until startRow.rowEnd){
            for (j in matrix[i].indices){
                sum += matrix[i][j]
            }
        }
        val result = CalculationResult(sum = sum)
        viewModelScope.launch{
            val deviceRef = dbRef.child("calculations").child(calculationId).child("calculation").child("secondaryDevices")
            val query = deviceRef.orderByChild("deviceId").equalTo("device_1")

            query.get().addOnSuccessListener { snapshot ->
                if (snapshot.children.iterator().hasNext()){
                    val key = snapshot.children.iterator().next().key
                    val updateStatus =  hashMapOf<String, Any>(
                        "status" to "completed",
                        "result" to result
                    )
                    viewModelScope.launch {
                        deviceRef.child(key!!).updateChildren(updateStatus).await()
                    }

                }else {
                    Log.e("MainDeviceViewModel", "No device data on server for id device_1")
                }
            }.addOnFailureListener {
                Log.e("MainDeviceViewModel", "Error getting device data on server", it)
            }
        }

    }


    private suspend fun sendDataToServer(serverData: ServerData) {
        dbRef.child("calculations").child(calculationId).setValue(serverData).await()
        Log.d("MainDeviceViewModel", "Data sent to server")
    }

    private fun listenForCompletion() {
        dbRef.child("calculations").child(calculationId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()){
                        Log.e("MainDeviceViewModel", "Calculation data does not exists on server!")
                        return
                    }
                    val serverData = snapshot.getValue(ServerData::class.java) ?: return
                    val updatedCalculationData = serverData.calculation

                    var completedDevices = 0
                    var sum = 0.0
                    updatedCalculationData.secondaryDevices.forEach{
                        if(it.status == "completed"){
                            sum += it.result?.sum ?: 0.0
                            completedDevices++
                        }
                    }

                    val numberOfDevices = updatedCalculationData.secondaryDevices.size
                    val progressValue = (completedDevices.toDouble() / numberOfDevices * 100).toInt()
                    progress.postValue(progressValue)
                    Log.d("MainDeviceViewModel", "Progress: $progressValue")

                    if (completedDevices == numberOfDevices){
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


    private fun onAllDevicesFinished(sum: Double){
        val resultText = "Final Sum of the Matrix: $sum"
        result.postValue(resultText)
        calculationInProgress.postValue(false)
        Log.d("MainDeviceViewModel", "Calculation finished. Total sum: $sum")
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            if (calculationId.isNotEmpty()){
                try {
                    dbRef.child("calculations").child(calculationId).removeValue().await()
                } catch (e: Exception){
                    Log.e("MainDeviceViewModel", "Error deleting data from server", e)
                }
            }
        }
    }
}