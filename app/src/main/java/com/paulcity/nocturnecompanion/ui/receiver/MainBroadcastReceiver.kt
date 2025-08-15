package com.paulcity.nocturnecompanion.ui.receiver

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.paulcity.nocturnecompanion.services.NocturneServiceBLE
import com.paulcity.nocturnecompanion.ui.UnifiedMainViewModel

class MainBroadcastReceiver(private val viewModel: UnifiedMainViewModel) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            NocturneServiceBLE.ACTION_SERVER_STATUS -> {
                viewModel.onServerStatusUpdate(
                    intent.getStringExtra(NocturneServiceBLE.EXTRA_SERVER_STATUS) ?: "Unknown",
                    intent.getBooleanExtra(NocturneServiceBLE.EXTRA_IS_RUNNING, false)
                )
            }
            NocturneServiceBLE.ACTION_CONNECTED_DEVICES -> {
                viewModel.onConnectedDevicesUpdate(intent.getStringExtra(NocturneServiceBLE.EXTRA_CONNECTED_DEVICES))
            }
            NocturneServiceBLE.ACTION_DEBUG_LOG -> {
                viewModel.onDebugLogReceived(intent.getStringExtra(NocturneServiceBLE.EXTRA_DEBUG_LOG))
            }
            NocturneServiceBLE.ACTION_STATE_UPDATED -> {
                viewModel.onStateUpdated(intent.getStringExtra(NocturneServiceBLE.EXTRA_JSON_DATA))
            }
            NocturneServiceBLE.ACTION_COMMAND_RECEIVED -> {
                viewModel.onCommandReceived(intent.getStringExtra(NocturneServiceBLE.EXTRA_JSON_DATA))
            }
            NocturneServiceBLE.ACTION_AUDIO_EVENT -> {
                viewModel.onAudioEvent(intent.getStringExtra(NocturneServiceBLE.EXTRA_AUDIO_EVENT))
            }
            NocturneServiceBLE.ACTION_NOTIFICATION -> {
                viewModel.onNotificationReceived(intent.getStringExtra(NocturneServiceBLE.EXTRA_NOTIFICATION_MESSAGE))
            }
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                viewModel.onBluetoothStateChanged(state == BluetoothAdapter.STATE_ON)
            }
            "com.paulcity.nocturnecompanion.REQUEST_WEATHER_REFRESH" -> {
                Log.d("MainBroadcastReceiver", "Received REQUEST_WEATHER_REFRESH broadcast")
                // Service is requesting current weather data to be sent
                viewModel.refreshWeatherForBle()
                Log.d("MainBroadcastReceiver", "Called viewModel.refreshWeatherForBle()")
            }
        }
    }
}
