package com.kawler.parrallelmatrix

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase

class ParrallelMatrix : Application(){
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        val database = FirebaseDatabase.getInstance("https://parrallel-matrix-default-rtdb.europe-west1.firebasedatabase.app")
    }
}