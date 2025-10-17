package com.example.rokidnice

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rokidnice.ui.theme.RokidniceTheme
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
import com.rokid.cxr.client.utils.ValueUtil

private const val TAG = "RokidController"

class MainActivity : ComponentActivity() {

    private val requiredPermissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }
    }.toTypedArray()

    private val bluetoothManager by lazy {
        getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RokidniceTheme {
                var hasPermissions by remember { mutableStateOf(false) }
                val requestPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                    onResult = { permissions -> hasPermissions = permissions.values.all { it } }
                )
                LaunchedEffect(Unit) { requestPermissionLauncher.launch(requiredPermissions) }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding).fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (hasPermissions) {
                            DeviceScanScreen(bluetoothAdapter)
                        } else {
                            Text("等待權限授予...")
                        }
                    }
                }
            }
        }
    }
}

// 這個畫面唯一的目標就是掃描和連線
@SuppressLint("MissingPermission")
@Composable
fun DeviceScanScreen(bluetoothAdapter: BluetoothAdapter?) {
    var statusText by remember { mutableStateOf("Disconnected") }
    var isScanning by remember { mutableStateOf(false) }
    val scannedDevices = remember { mutableStateListOf<BluetoothDevice>() }

    val context = LocalContext.current
    val leScanner = bluetoothAdapter?.bluetoothLeScanner

    val scanCallback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.device?.let { device ->
                    if (scannedDevices.none { it.address == device.address } && !device.name.isNullOrBlank()) {
                        Log.d(TAG, "發現設備: ${device.name} (${device.address})")
                        scannedDevices.add(device)
                    }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "掃描失敗，錯誤碼: $errorCode")
                isScanning = false
            }
        }
    }

    val bluetoothStatusCallback = remember {
        object : BluetoothStatusCallback {
            override fun onConnectionInfo(socketUuid: String?, macAddress: String?, rokidAccount: String?, glassesType: Int) {
                if (socketUuid != null && macAddress != null) {
                    CxrApi.getInstance().connectBluetooth(context, socketUuid, macAddress, this)
                }
            }
            override fun onConnected() {
                Log.d(TAG, "連線成功！心跳已建立。")
                statusText = "Connected"
            }
            override fun onDisconnected() {
                Log.d(TAG, "連線中斷。")
                statusText = "Disconnected"
            }
            override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) {
                Log.e(TAG, "連線失敗: ${errorCode?.name}")
                statusText = "Failed: ${errorCode?.name}"
            }
        }
    }

    DisposableEffect(Unit) { onDispose { leScanner?.stopScan(scanCallback) } }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("連線狀態: $statusText", fontSize = 22.sp, color = if (statusText == "Connected") Color.Green else Color.Red)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (!isScanning) {
                    scannedDevices.clear()
                    val scanFilter = ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString("00009100-0000-1000-8000-00805f9b34fb")).build()
                    val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                    leScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
                    isScanning = true
                } else {
                    leScanner?.stopScan(scanCallback)
                    isScanning = false
                }
            },
            enabled = bluetoothAdapter?.isEnabled == true && statusText != "Connected"
        ) {
            Text(if (isScanning) "停止掃描" else "掃描 Rokid 眼鏡")
        }

        if (isScanning) CircularProgressIndicator(modifier = Modifier.padding(top=16.dp))

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp)) {
            items(scannedDevices) { device ->
                Column(modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        leScanner?.stopScan(scanCallback)
                        isScanning = false
                        statusText = "正在初始化與 ${device.name} 的連線..."
                        CxrApi.getInstance().initBluetooth(context, device, bluetoothStatusCallback)
                    }
                    .padding(vertical = 12.dp)) {
                    Text(device.name, fontSize = 18.sp)
                    Text(device.address, fontSize = 14.sp, color = Color.Gray)
                }
                Divider()
            }
        }
    }
}
