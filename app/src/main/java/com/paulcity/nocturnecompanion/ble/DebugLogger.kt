package com.paulcity.nocturnecompanion.ble

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

class DebugLogger {
    companion object {
        private const val TAG = "DebugLogger"
        private const val MAX_LOG_SIZE = 1000
        private val gson = Gson()
        private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
    
    data class DebugLogEntry(
        @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis(),
        @SerializedName("level") val level: BleConstants.DebugLevel,
        @SerializedName("type") val type: String,
        @SerializedName("message") val message: String,
        @SerializedName("data") val data: Map<String, Any>? = null
    ) {
        fun toJson(): String = gson.toJson(this)
        
        fun toFormattedString(): String {
            val time = dateFormat.format(Date(timestamp))
            val dataStr = data?.let { " | ${gson.toJson(it)}" } ?: ""
            return "$time [$level] $type: $message$dataStr"
        }
    }
    
    private val logBuffer = ConcurrentLinkedQueue<DebugLogEntry>()
    private val _logFlow = MutableSharedFlow<DebugLogEntry>(replay = 50)
    val logFlow = _logFlow.asSharedFlow()
    
    fun log(
        level: BleConstants.DebugLevel,
        type: String,
        message: String,
        data: Map<String, Any>? = null
    ) {
        val entry = DebugLogEntry(
            level = level,
            type = type,
            message = message,
            data = data
        )
        
        // Add to buffer
        logBuffer.offer(entry)
        while (logBuffer.size > MAX_LOG_SIZE) {
            logBuffer.poll()
        }
        
        // Emit to flow
        _logFlow.tryEmit(entry)
        
        // Log to Android logger
        val androidLogMessage = entry.toFormattedString()
        when (level) {
            BleConstants.DebugLevel.VERBOSE -> Log.v(TAG, androidLogMessage)
            BleConstants.DebugLevel.DEBUG -> Log.d(TAG, androidLogMessage)
            BleConstants.DebugLevel.INFO -> Log.i(TAG, androidLogMessage)
            BleConstants.DebugLevel.WARNING -> Log.w(TAG, androidLogMessage)
            BleConstants.DebugLevel.ERROR -> Log.e(TAG, androidLogMessage)
        }
    }
    
    fun getRecentLogs(count: Int = 100): List<DebugLogEntry> {
        return logBuffer.toList().takeLast(count)
    }
    
    fun clearLogs() {
        logBuffer.clear()
    }
    
    // Convenience methods
    fun verbose(type: String, message: String, data: Map<String, Any>? = null) = 
        log(BleConstants.DebugLevel.VERBOSE, type, message, data)
        
    fun debug(type: String, message: String, data: Map<String, Any>? = null) = 
        log(BleConstants.DebugLevel.DEBUG, type, message, data)
        
    fun info(type: String, message: String, data: Map<String, Any>? = null) = 
        log(BleConstants.DebugLevel.INFO, type, message, data)
        
    fun warning(type: String, message: String, data: Map<String, Any>? = null) = 
        log(BleConstants.DebugLevel.WARNING, type, message, data)
        
    fun error(type: String, message: String, data: Map<String, Any>? = null) = 
        log(BleConstants.DebugLevel.ERROR, type, message, data)
    
    // Common log types
    object LogType {
        const val CONNECTION = "CONNECTION"
        const val DISCONNECTION = "DISCONNECTION"
        const val COMMAND_RECEIVED = "COMMAND_RECEIVED"
        const val COMMAND_EXECUTED = "COMMAND_EXECUTED"
        const val STATE_UPDATED = "STATE_UPDATED"
        const val ERROR = "ERROR"
        const val MTU_CHANGED = "MTU_CHANGED"
        const val CHARACTERISTIC_WRITE = "CHARACTERISTIC_WRITE"
        const val NOTIFICATION_SENT = "NOTIFICATION_SENT"
        const val ADVERTISING = "ADVERTISING"
        const val MEDIA_SESSION = "MEDIA_SESSION"
        const val PERMISSION = "PERMISSION"
        const val INITIALIZATION = "INITIALIZATION"
        const val TIME_SYNC = "TIME_SYNC"
    }
}