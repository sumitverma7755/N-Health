package com.nhealth.watchstatus

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.nhealth.watchstatus.ui.BleCompanionApp
import com.nhealth.watchstatus.ui.theme.NHealthTheme
import com.nhealth.watchstatus.viewmodel.BLEViewModel
import com.nhealth.watchstatus.viewmodel.BLEViewModelFactory

class MainActivity : ComponentActivity() {

    private val hasPermissionsState = mutableStateOf(false)

    private val viewModel: BLEViewModel by viewModels {
        val repository = (application as NHealthApplication).appContainer.bleRepository
        BLEViewModelFactory(repository)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermissionsState.value = hasRequiredPermissions()
        if (hasPermissionsState.value) {
            requestBluetoothEnableIfNeeded()
        }
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // The BLE manager observes the adapter state during operations.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hasPermissionsState.value = hasRequiredPermissions()
        if (!hasPermissionsState.value) {
            permissionLauncher.launch(requiredPermissions())
        } else {
            requestBluetoothEnableIfNeeded()
        }

        setContent {
            val hasPermissions by hasPermissionsState
            NHealthTheme {
                BleCompanionApp(
                    viewModel = viewModel,
                    hasPermissions = hasPermissions,
                    onRequestPermissions = {
                        permissionLauncher.launch(requiredPermissions())
                    }
                )
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }

        permissions += Manifest.permission.ACCESS_FINE_LOCATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }

        return permissions.toTypedArray()
    }

    @Suppress("DEPRECATION")
    private fun requestBluetoothEnableIfNeeded() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (adapter.isEnabled) {
            return
        }

        bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }
}
