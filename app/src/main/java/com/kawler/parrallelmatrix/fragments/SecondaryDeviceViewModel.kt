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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID


class SecondaryDeviceViewModel : ViewModel() {
    private val dbRef: DatabaseReference =
        FirebaseDatabase.getInstance("https://parrallel-matrix-default-rtdb.europe-west1.firebasedatabase.app/").reference
    val progress = MutableLiveData<Int>(0)
    val result = MutableLiveData<String>("")
    val status = MutableLiveData<String>("Ожидание данных от главного устройства")
    val deviceIdValid = MutableLiveData<Boolean>(false)
    private var calculationId: String? = null
    private lateinit var deviceId: String
    private var matrix: List<List<Double>>? = null
    private var currentDeviceData: SecondaryDeviceData? = null

    fun setDeviceId() {
        deviceId = UUID.randomUUID().toString()
        deviceIdValid.postValue(true)
        status.postValue("Ожидание данных от главного устройства...")
        sendDeviceToServer()
    }

    private fun sendDeviceToServer() {
        viewModelScope.launch {
            dbRef.child("calculations")
                .orderByKey()
                .limitToLast(1)
                .get()
                .addOnSuccessListener { snapshot ->
                    if (!snapshot.exists() || !snapshot.hasChildren()) {
                        Log.e("SecondaryDeviceViewModel", "Calculation data does not exist on server!")
                        status.postValue("Ошибка при получении данных, повторите попытку позже.")
                        return@addOnSuccessListener
                    }

                    val childSnapshot = snapshot.children.last()
                    val calculationSnapshot = childSnapshot.child("calculation")
                    val serverData = childSnapshot.child("calculationId")
                    val calculationData = calculationSnapshot.getValue(CalculationData::class.java)

                    if (calculationData == null || !serverData.exists()) {
                        status.postValue("Ошибка при получении данных, повторите попытку позже.")
                        return@addOnSuccessListener
                    }
                    calculationId = serverData.getValue(String::class.java)
                    viewModelScope.launch(Dispatchers.IO) {
                        registerDeviceToServer()
                    }
                }
                .addOnFailureListener {
                    Log.e("SecondaryDeviceViewModel", "Error adding device to server", it)
                    status.postValue("Ошибка при получении данных, повторите попытку позже.")
                }
        }
    }

    private suspend fun registerDeviceToServer() {
        val deviceRef = dbRef.child("calculations").child(calculationId!!).child("calculation")
            .child("secondaryDevices")
        val newDevice = SecondaryDeviceData(
            deviceId = deviceId,
            status = "waiting",
        )
        deviceRef.push().setValue(newDevice).await()
        status.postValue("Подключено к вычислению, ожидание расчета...")
        startListeningForData()
    }

    private fun startListeningForData() {
        dbRef.child("calculations")
            .child(calculationId!!)
            .child("calculation")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val calculationData = snapshot.getValue(CalculationData::class.java)
                    if (calculationData == null) {
                        Log.e("SecondaryDeviceViewModel", "No calculation data with id $calculationId")
                        status.postValue("Ошибка при получении данных, повторите попытку позже.")
                        return
                    }
                    val secondaryDevicesMap = calculationData.secondaryDevices
                    if (secondaryDevicesMap.isEmpty()){
                        return
                    }

                    matrix = calculationData.matrix
                    currentDeviceData = secondaryDevicesMap.values.find { it.deviceId == deviceId }

                    if (currentDeviceData == null) {
                        status.postValue("Ожидание начала расчета...")
                        return
                    }


                    if (currentDeviceData!!.calculationPart != null && currentDeviceData?.status == "waiting") {
                        status.postValue("Идет расчет...")
                        calculatePartialSum(calculationData, currentDeviceData!!)
                        dbRef.child("calculations").child(calculationId!!).child("calculation").removeEventListener(this)
                    } else if (currentDeviceData?.status == "completed") {
                        status.postValue("Расчет завершен, ожидайте результата на главном устройстве...")
                        dbRef.child("calculations").child(calculationId!!).child("calculation").removeEventListener(this)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("SecondaryDeviceViewModel", "Listener cancelled", error.toException())
                    result.postValue("Error during calculation: ${error.message}")
                    status.postValue("Ошибка при получении данных, повторите попытку позже.")
                }
            })
    }


    private fun calculatePartialSum(calculationData: CalculationData, currentDeviceData: SecondaryDeviceData) {
        viewModelScope.launch {
            try {
                val partialMatrix = getPartialMatrix(calculationData, currentDeviceData)
                val sum = calculateSum(partialMatrix)
                sendPartialResultToServer(sum)
            } catch (e: Exception) {
                Log.e("SecondaryDeviceViewModel", "Error during calculation", e)
                result.postValue("Error during calculation: ${e.message}")
                status.postValue("Ошибка при расчете данных.")
            }
        }
    }

    private fun getPartialMatrix(calculationData: CalculationData, currentDeviceData: SecondaryDeviceData): List<List<Double>> {
        val startRow = currentDeviceData.calculationPart!!
        val rows = matrix?.size ?: 0
        if (matrix == null) {
            Log.e("SecondaryDeviceViewModel", "Matrix is null while getting partial matrix")
            throw Exception("Matrix is null")
        }
        val partialMatrix = mutableListOf<List<Double>>()

        for (i in startRow.rowStart..startRow.rowEnd) {
            if (i < rows) {
                val currentRow = matrix!![i].toMutableList()
                partialMatrix.add(currentRow)
            }
        }
        return partialMatrix
    }

    private fun calculateSum(partialMatrix: List<List<Double>>): Double {
        var sum = 0.0
        for (row in partialMatrix) {
            for (element in row) {
                sum += element
            }
        }
        return sum
    }

    private suspend fun sendPartialResultToServer(sum: Double) {
        if (calculationId == null) {
            Log.e("SecondaryDeviceViewModel", "Calculation id is null while trying to send results")
            status.postValue("Ошибка при отправке данных на сервер, повторите попытку позже.")
            return
        }
        val deviceRef = dbRef.child("calculations").child(calculationId!!).child("calculation")
            .child("secondaryDevices")
        val query = deviceRef.orderByChild("deviceId").equalTo(deviceId)
        query.get().addOnSuccessListener { snapshot ->
            if (snapshot.children.iterator().hasNext()) {
                val key = snapshot.children.iterator().next().key
                viewModelScope.launch {
                    val result = CalculationResult(sum = sum)
                    val updateStatus = hashMapOf<String, Any>(
                        "status" to "completed",
                        "result" to result
                    )
                    deviceRef.child(key!!).updateChildren(updateStatus).await()
                    status.postValue("Расчет завершен, ожидайте результата на главном устройстве...")
                }
            } else {
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



