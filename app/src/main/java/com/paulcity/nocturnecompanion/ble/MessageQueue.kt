package com.paulcity.nocturnecompanion.ble

import android.bluetooth.BluetoothDevice
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.selects.select
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Priority-based message queue for BLE transmissions
 * Prevents jamming by managing flow control and prioritization
 */
class MessageQueue(
    private val scope: CoroutineScope,
    private val sendFunction: suspend (BluetoothDevice, UUID, ByteArray) -> Boolean
) {
    companion object {
        private const val TAG = "MessageQueue"
        
        // Queue sizes
        private const val HIGH_PRIORITY_QUEUE_SIZE = 50
        private const val NORMAL_PRIORITY_QUEUE_SIZE = 100
        private const val BULK_QUEUE_SIZE = 200
        
        // Rate limits (messages per second)
        private const val MAX_MESSAGES_PER_SECOND = 100
        private const val MAX_BULK_MESSAGES_PER_SECOND = 200
        
        // Timing (defaults)
        private const val DEFAULT_MIN_MESSAGE_INTERVAL_MS = 10L
        private const val DEFAULT_MIN_BULK_INTERVAL_MS = 5L
        private const val CONGESTION_BACKOFF_MS = 50L
    }
    
    // Configurable settings
    private var minBulkIntervalMs = DEFAULT_MIN_BULK_INTERVAL_MS
    private var useBinaryProtocol = true
    
    enum class Priority {
        HIGH,    // Time sync, critical state updates
        NORMAL,  // Regular state updates
        BULK     // Album art chunks
    }
    
    data class Message(
        val device: BluetoothDevice,
        val characteristicUuid: UUID,
        val data: ByteArray,
        val priority: Priority,
        val timestamp: Long = System.currentTimeMillis(),
        val retryCount: Int = 0,
        val callback: ((Boolean) -> Unit)? = null,
        val isBinary: Boolean = false, // Flag for binary protocol messages
        val isTestTransfer: Boolean = false // Flag for test transfers that need fast timing
    )
    
    // Separate channels for different priorities
    private val highPriorityChannel = Channel<Message>(HIGH_PRIORITY_QUEUE_SIZE)
    private val normalPriorityChannel = Channel<Message>(NORMAL_PRIORITY_QUEUE_SIZE)
    private val bulkChannel = Channel<Message>(BULK_QUEUE_SIZE)
    
    // Congestion tracking per device
    private val deviceCongestion = ConcurrentHashMap<String, CongestionState>()
    private val _congestionState = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val congestionState: StateFlow<Map<String, Boolean>> = _congestionState
    
    // Active bulk transfers per device
    private val activeBulkTransfers = ConcurrentHashMap<String, Job>()
    
    private var processingJob: Job? = null
    private var lastSendTime = 0L
    
    data class CongestionState(
        var failureCount: Int = 0,
        var lastFailureTime: Long = 0,
        var backoffMs: Long = 0
    )
    
    init {
        startProcessing()
    }
    
    fun enqueue(message: Message): Boolean {
        return when (message.priority) {
            Priority.HIGH -> highPriorityChannel.trySend(message).isSuccess
            Priority.NORMAL -> normalPriorityChannel.trySend(message).isSuccess
            Priority.BULK -> {
                // For bulk transfers, manage per-device queuing
                if (message.characteristicUuid == BleConstants.ALBUM_ART_TX_CHAR_UUID) {
                    // Cancel any existing bulk transfer for this device
                    activeBulkTransfers[message.device.address]?.cancel()
                }
                bulkChannel.trySend(message).isSuccess
            }
        }
    }
    
    fun pauseBulkTransfers(deviceAddress: String) {
        activeBulkTransfers[deviceAddress]?.cancel()
        Log.d(TAG, "Paused bulk transfers for device: $deviceAddress")
    }
    
    fun resumeBulkTransfers(deviceAddress: String) {
        // Bulk transfers will resume automatically when processed
        Log.d(TAG, "Resumed bulk transfers for device: $deviceAddress")
    }
    
    fun clearDeviceQueue(deviceAddress: String) {
        // Clear pending messages for disconnected device
        scope.launch {
            // This is a simplified version - in production, you'd filter channels
            deviceCongestion.remove(deviceAddress)
            activeBulkTransfers[deviceAddress]?.cancel()
            activeBulkTransfers.remove(deviceAddress)
            updateCongestionState()
        }
    }
    
    private fun startProcessing() {
        processingJob = scope.launch {
            while (isActive) {
                // Process in priority order
                // Try high priority first
                val message = highPriorityChannel.tryReceive().getOrNull()
                if (message != null) {
                    processMessage(message, DEFAULT_MIN_MESSAGE_INTERVAL_MS)
                } else {
                    // Try normal priority
                    val normalMessage = normalPriorityChannel.tryReceive().getOrNull()
                    if (normalMessage != null) {
                        processMessage(normalMessage, DEFAULT_MIN_MESSAGE_INTERVAL_MS)
                    } else {
                        // Try bulk
                        val bulkMessage = bulkChannel.tryReceive().getOrNull()
                        if (bulkMessage != null) {
                            processMessage(bulkMessage, minBulkIntervalMs)
                        } else {
                            // No messages, wait a bit
                            delay(10)
                        }
                    }
                }
            }
        }
    }
    
    private suspend fun processMessage(message: Message, minInterval: Long) {
        val deviceAddress = message.device.address
        val congestion = deviceCongestion.getOrPut(deviceAddress) { CongestionState() }
        
        // For test transfers, use minimal delays
        val effectiveMinInterval = if (message.isTestTransfer) {
            0L // No artificial delays for test transfers
        } else {
            minInterval
        }
        
        // Apply rate limiting (skip for test transfers)
        if (!message.isTestTransfer) {
            val timeSinceLastSend = System.currentTimeMillis() - lastSendTime
            if (timeSinceLastSend < effectiveMinInterval) {
                delay(effectiveMinInterval - timeSinceLastSend)
            }
        }
        
        // Apply congestion backoff (reduced for test transfers)
        if (congestion.backoffMs > 0 && !message.isTestTransfer) {
            delay(congestion.backoffMs)
        }
        
        // Attempt to send
        val success = try {
            if (message.isBinary && message.priority == Priority.BULK) {
                // Log binary chunk sends less frequently to reduce log spam
                if (message.retryCount == 0 && System.currentTimeMillis() % 10 == 0L) {
                    Log.d(TAG, "Sending binary chunk to ${deviceAddress}")
                }
            } else if (!message.isBinary) {
                Log.d(TAG, "Sending JSON message to ${deviceAddress}: ${message.data.size} bytes")
            }
            sendFunction(message.device, message.characteristicUuid, message.data)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
            false
        }
        
        lastSendTime = System.currentTimeMillis()
        
        // Update congestion state
        if (success) {
            // Success - reduce backoff
            congestion.failureCount = 0
            congestion.backoffMs = maxOf(0, congestion.backoffMs - 10)
        } else {
            // Failure - increase backoff
            congestion.failureCount++
            congestion.lastFailureTime = System.currentTimeMillis()
            congestion.backoffMs = minOf(
                CONGESTION_BACKOFF_MS * congestion.failureCount,
                1000L // Max 1 second backoff
            )
            
            // Retry high priority messages and binary protocol start/end messages
            if ((message.priority == Priority.HIGH && message.retryCount < 3) ||
                (message.isBinary && message.priority == Priority.NORMAL && message.retryCount < 2)) {
                delay(congestion.backoffMs)
                enqueue(message.copy(retryCount = message.retryCount + 1))
            }
            // Don't retry bulk binary chunks - the protocol handles missing chunks
        }
        
        // Update congestion state for UI
        updateCongestionState()
        
        // Invoke callback
        message.callback?.invoke(success)
    }
    
    private fun updateCongestionState() {
        val congestionMap = deviceCongestion.mapValues { (_, state) ->
            state.failureCount > 2 || state.backoffMs > 100
        }
        _congestionState.value = congestionMap
    }
    
    fun getQueueDepths(): Triple<Int, Int, Int> {
        return Triple(
            highPriorityChannel.tryReceive().isSuccess.let { if (it) 1 else 0 },
            normalPriorityChannel.tryReceive().isSuccess.let { if (it) 1 else 0 },
            bulkChannel.tryReceive().isSuccess.let { if (it) 1 else 0 }
        )
    }
    
    fun stop() {
        processingJob?.cancel()
        highPriorityChannel.close()
        normalPriorityChannel.close()
        bulkChannel.close()
    }
    
    /**
     * Get queue statistics including binary vs JSON message distribution
     */
    fun getQueueStats(): QueueStats {
        var binaryCount = 0
        var jsonCount = 0
        var totalSize = 0L
        
        // Note: This is a simplified stats collection
        // In production, you'd track these counters during enqueue/dequeue
        return QueueStats(
            highPriorityDepth = highPriorityChannel.tryReceive().isSuccess.let { if (it) 1 else 0 },
            normalPriorityDepth = normalPriorityChannel.tryReceive().isSuccess.let { if (it) 1 else 0 },
            bulkDepth = bulkChannel.tryReceive().isSuccess.let { if (it) 1 else 0 },
            binaryMessageCount = binaryCount,
            jsonMessageCount = jsonCount,
            totalBytesQueued = totalSize
        )
    }
    
    data class QueueStats(
        val highPriorityDepth: Int,
        val normalPriorityDepth: Int,
        val bulkDepth: Int,
        val binaryMessageCount: Int,
        val jsonMessageCount: Int,
        val totalBytesQueued: Long
    )
    
    /**
     * Update queue settings
     */
    fun updateSettings(chunkSize: Int, chunkDelayMs: Int, useBinaryProtocol: Boolean) {
        this.minBulkIntervalMs = chunkDelayMs.toLong()
        this.useBinaryProtocol = useBinaryProtocol
        
        Log.d(TAG, "Settings updated - ChunkDelay: ${chunkDelayMs}ms, Binary: $useBinaryProtocol")
    }
}