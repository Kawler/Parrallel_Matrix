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
import com.kawler.parrallelmatrix.models.CalculationResult
import com.kawler.parrallelmatrix.models.SecondaryDeviceData
import com.kawler.parrallelmatrix.models.ServerData
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class SecondaryDeviceViewModel : ViewModel() {
    private val dbRef: DatabaseReference = FirebaseDatabase.getInstance("https://parrallel-matrix-default-rtdb.europe-west1.firebasedatabase.app/").reference
    val progress = MutableLiveData<Int>(0)
    val result = MutableLiveData<String>("")
    val status = MutableLiveData<String>("Ожидание ID устройства")
    val deviceIdValid = MutableLiveData<Boolean>(false)
    private var calculationId: String? = null
    private lateinit var deviceId: String
    private var matrix: List<List<Double>>? = null


    fun setDeviceId(id: Int) {
        deviceId = "device_$id"
        deviceIdValid.postValue(true)
        status.postValue("Ожидание данных от главного устройства...")
        startListeningForData()
    }

    private fun startListeningForData() {
        dbRef.child("calculations")
            .orderByKey()
            .limitToLast(1)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists() || !snapshot.hasChildren()) {
                        Log.e("SecondaryDeviceViewModel", "Calculation data does not exist on server!")
                        status.postValue("Ошибка при получении данных, повторите попытку позже.")
                        return
                    }

                    val childSnapshot = snapshot.children.last()
                    val serverData = childSnapshot.getValue(ServerData::class.java)

                    if (serverData == null){
                        status.postValue("Ошибка при получении данных, повторите попытку позже.")
                        return
                    }

                    calculationId = serverData.calculationId

                    val calculationData = serverData.calculation
                    if (calculationData == null) {
                        Log.e("SecondaryDeviceViewModel", "No calculation data with id $calculationId")
                        status.postValue("Ошибка при получении данных, повторите попытку позже.")
                        return
                    }

                    val currentDeviceData = calculationData.secondaryDevices.find { it.deviceId == deviceId }
                    if (currentDeviceData == null){
                        Log.e("SecondaryDeviceViewModel", "No device data for id $deviceId")
                        status.postValue("Ошибка при получении данных, повторите попытку позже.")
                        return
                    }

                    status.postValue("Получены данные, идет расчет...")
                    matrix = calculationData.matrix
                    if (matrix == null){
                        Log.e("SecondaryDeviceViewModel", "Matrix is null while getting partial matrix")
                        status.postValue("Ошибка при получении данных, повторите попытку позже.")
                        return
                    }

                    calculatePartialSum(calculationData, currentDeviceData)
                    dbRef.child("calculations").removeEventListener(this)
                }


                override fun onCancelled(error: DatabaseError) {
                    Log.e("SecondaryDeviceViewModel", "Listener cancelled", error.toException())
                    result.postValue("Error during calculation: ${error.message}")
                    status.postValue("Ошибка при получении данных, повторите попытку позже.")
                }
            })
    }



    private fun calculatePartialSum(calculationData: CalculationData, currentDeviceData: SecondaryDeviceData){
        viewModelScope.launch {
            try {
                val partialMatrix = getPartialMatrix(calculationData, currentDeviceData)
                val sum = calculateSum(partialMatrix)
                sendPartialResultToServer(sum)
            } catch(e: Exception){
                Log.e("SecondaryDeviceViewModel", "Error during calculation", e)
                result.postValue("Error during calculation: ${e.message}")
                status.postValue("Ошибка при расчете данных.")
            }
        }
    }

    private fun getPartialMatrix(calculationData: CalculationData, currentDeviceData: SecondaryDeviceData) : List<List<Double>>{
        val startRow = currentDeviceData.calculationPart!!
        val cols = calculationData.matrixSize.cols
        val rows = matrix?.size ?: 0

        if (matrix == null) {
            Log.e("SecondaryDeviceViewModel", "Matrix is null while getting partial matrix")
            throw Exception("Matrix is null")
        }

        val partialMatrix = mutableListOf<List<Double>>()

        for (i in startRow.rowStart..startRow.rowEnd){
            if (i < rows){
                val currentRow = matrix!![i].toMutableList()
                partialMatrix.add(currentRow)
            }
        }

        return partialMatrix
    }

    private fun calculateSum(partialMatrix: List<List<Double>>) : Double{
        var sum = 0.0
        for (row in partialMatrix){
            for (element in row){
                sum+=element
            }
        }
        return sum
    }


    private suspend fun sendPartialResultToServer(sum: Double) {
        if (calculationId == null){
            Log.e("SecondaryDeviceViewModel", "Calculation id is null while trying to send results")
            status.postValue("Ошибка при отправке данных на сервер, повторите попытку позже.")
            return
        }
        val deviceRef = dbRef.child("calculations").child(calculationId!!).child("calculation").child("secondaryDevices")
        val query = deviceRef.orderByChild("deviceId").equalTo(deviceId)


        query.get().addOnSuccessListener { snapshot ->
            if (snapshot.children.iterator().hasNext()){
                val key = snapshot.children.iterator().next().key
                viewModelScope.launch {
                    val result = CalculationResult(sum = sum)
                    val updateStatus =   hashMapOf<String, Any>(
                        "status" to "completed",
                        "result" to result
                    )
                    deviceRef.child(key!!).updateChildren(updateStatus).await()
                    status.postValue("Расчет завершен, ожидайте результата на главном устройстве...")
                }

            }else {
                status.postValue("Ошибка при отправке данных на сервер, повторите попытку позже.")
                Log.e("SecondaryDeviceViewModel", "No device data on server for id $deviceId")
            }
        }.addOnFailureListener {
            status.postValue("Ошибка при отправке данных на сервер, повторите попытку позже.")
            Log.e("SecondaryDeviceViewModel", "Error getting device data on server", it)
        }

    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            dbRef.removeEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {}
                override fun onCancelled(error: DatabaseError) {
                    Log.e("SecondaryDeviceViewModel", "Listener cancelled", error.toException())
                }
            })
        }
    }
}