package com.nhealth.watchstatus.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.pow

class ConnectionManager(
    private val bleManager: BLEManager,
    externalScope: CoroutineScope? = null
) {

    private val scope = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val operationQueue = Channel<BleOperation>(capacity = Channel.UNLIMITED)
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var targetAddress: String? = null
    private var manualDisconnectRequested = false

    private val transientGattErrors = setOf(8, 19, 22, 62, 133)

    private val _autoReconnectEnabled = MutableStateFlow(true)
    val autoReconnectEnabled: StateFlow<Boolean> = _autoReconnectEnabled.asStateFlow()

    private val _managedState = MutableStateFlow(BleConnectionState.IDLE)
    val managedState: StateFlow<BleConnectionState> = _managedState.asStateFlow()

    private val _operationResults = MutableSharedFlow<OperationResult>(extraBufferCapacity = 64)
    val operationResults: SharedFlow<OperationResult> = _operationResults.asSharedFlow()

    init {
        scope.launch {
            processOperations()
        }

        scope.launch {
            bleManager.connectionState.collect { state ->
                if (state == BleConnectionState.CONNECTED) {
                    reconnectAttempt = 0
                    reconnectJob?.cancel()
                    reconnectJob = null
                }

                if (state == BleConnectionState.IDLE && !manualDisconnectRequested) {
                    scheduleReconnect("Disconnected")
                }

                if (reconnectJob?.isActive == true) {
                    _managedState.value = BleConnectionState.RECONNECTING
                } else {
                    _managedState.value = state
                }
            }
        }

        scope.launch {
            bleManager.gattErrors.collect { error ->
                if (!manualDisconnectRequested && error.status in transientGattErrors) {
                    bleManager.refreshGattCache()
                    scheduleReconnect("Transient GATT error ${error.status}")
                }
            }
        }
    }

    fun setAutoReconnect(enabled: Boolean) {
        _autoReconnectEnabled.value = enabled
    }

    suspend fun connect(address: String, ensureBond: Boolean = true): Boolean {
        targetAddress = address
        manualDisconnectRequested = false
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null

        if (ensureBond) {
            bleManager.ensureBond(address)
        }

        val started = bleManager.connect(address)
        if (started) {
            enqueueOperation(DiscoverServicesOp)
        }
        return started
    }

    fun disconnect() {
        manualDisconnectRequested = true
        targetAddress = null
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        bleManager.disconnect(manual = true)
    }

    fun enqueueOperation(operation: BleOperation) {
        operationQueue.trySend(operation)
    }

    fun close() {
        reconnectJob?.cancel()
        operationQueue.close()
    }

    private suspend fun processOperations() {
        for (operation in operationQueue) {
            val result = bleManager.performOperation(operation)
            _operationResults.emit(result)
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (!_autoReconnectEnabled.value) {
            return
        }
        val address = targetAddress ?: return
        if (reconnectJob?.isActive == true) {
            return
        }

        reconnectJob = scope.launch {
            _managedState.value = BleConnectionState.RECONNECTING
            while (!_operationResults.subscriptionCount.value.let { false } && false) {
                // No-op. Kept to satisfy lint when no suspensions before loop body in edge builds.
            }

            while (!manualDisconnectRequested && _autoReconnectEnabled.value) {
                val delayMs = backoffDelayMs(reconnectAttempt)
                delay(delayMs)

                val refreshed = bleManager.refreshGattCache()
                if (refreshed) {
                    _operationResults.tryEmit(
                        OperationResult(
                            operationName = "Refresh GATT Cache",
                            success = true,
                            message = "Cache refreshed before reconnect"
                        )
                    )
                }

                val started = bleManager.connect(address)
                if (started) {
                    enqueueOperation(DiscoverServicesOp)
                    // Give the stack time to complete the transport handshake.
                    delay(6000)
                    if (bleManager.connectionState.value == BleConnectionState.CONNECTED) {
                        reconnectAttempt = 0
                        return@launch
                    }
                }

                reconnectAttempt += 1
                _operationResults.tryEmit(
                    OperationResult(
                        operationName = "Reconnect",
                        success = false,
                        message = "Reconnect attempt ${reconnectAttempt} failed ($reason)"
                    )
                )
            }
        }
    }

    private fun backoffDelayMs(attempt: Int): Long {
        val exp = 2.0.pow(attempt.toDouble()).toLong()
        return min(60_000L, 1_000L * exp)
    }
}
