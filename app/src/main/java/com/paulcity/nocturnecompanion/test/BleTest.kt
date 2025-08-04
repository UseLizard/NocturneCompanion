package com.paulcity.nocturnecompanion.test

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context

class BleTest(private val context: Context) {
    @android.annotation.SuppressLint("MissingPermission")
    fun testApi() {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        
        // Test 1: adapter access
        val adapter = bluetoothManager.adapter
        
        // Test 2: openGattServer access  
        val server = bluetoothManager.openGattServer(context, null)
    }
}