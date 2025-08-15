package com.paulcity.nocturnecompanion.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.paulcity.nocturnecompanion.services.NocturneServiceBLE
import com.paulcity.nocturnecompanion.ui.composables.UnifiedMainScreen
import com.paulcity.nocturnecompanion.ui.receiver.MainBroadcastReceiver
import com.paulcity.nocturnecompanion.ui.theme.NocturneCompanionTheme

@SuppressLint("MissingPermission")
class UnifiedMainActivity : ComponentActivity() {

    private val viewModel: UnifiedMainViewModel by viewModels()
    private lateinit var receiver: MainBroadcastReceiver
    private var isReceiverRegistered = false

    private val scanPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.all { it }) {
                viewModel.scanForDevices()
            }
        }

    private val startServicePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val hasConnect = permissions.getOrDefault(Manifest.permission.BLUETOOTH_CONNECT, false)
            val hasAdvertise = permissions.getOrDefault(Manifest.permission.BLUETOOTH_ADVERTISE, false)

            if (hasConnect && hasAdvertise) {
                viewModel.startNocturneService()
            } else {
                Log.w("UnifiedMainActivity", "Required Bluetooth permissions were denied. Connect: $hasConnect, Advertise: $hasAdvertise")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiver = MainBroadcastReceiver(viewModel)
        registerBroadcastReceivers()

        setContent {
            NocturneCompanionTheme {
                UnifiedMainScreen(
                    viewModel = viewModel,
                    onScanClick = { checkPermissionsAndScan() },
                    onStartServer = { requestPermissionsAndStart() }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterBroadcastReceivers()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerBroadcastReceivers() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(NocturneServiceBLE.ACTION_SERVER_STATUS)
                addAction(NocturneServiceBLE.ACTION_CONNECTED_DEVICES)
                addAction(NocturneServiceBLE.ACTION_DEBUG_LOG)
                addAction(NocturneServiceBLE.ACTION_STATE_UPDATED)
                addAction(NocturneServiceBLE.ACTION_COMMAND_RECEIVED)
                addAction(NocturneServiceBLE.ACTION_AUDIO_EVENT)
                addAction(NocturneServiceBLE.ACTION_NOTIFICATION)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction("com.paulcity.nocturnecompanion.REQUEST_WEATHER_REFRESH")
            }

            if (Build.VERSION.SDK_INT >= 34) {
                LocalBroadcastManager.getInstance(this).registerReceiver(receiver, filter)
            } else {
                registerReceiver(receiver, filter)
            }
            isReceiverRegistered = true
        }
    }

    private fun unregisterBroadcastReceivers() {
        if (isReceiverRegistered) {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
                } else {
                    unregisterReceiver(receiver)
                }
                isReceiverRegistered = false
            } catch (e: IllegalArgumentException) {
                Log.w("UnifiedMainActivity", "Receiver was not registered", e)
            }
        }
    }

    private fun checkPermissionsAndScan() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        scanPermissionsLauncher.launch(requiredPermissions)
    }

    private fun requestPermissionsAndStart() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        startServicePermissionLauncher.launch(permissions.toTypedArray())
    }
}