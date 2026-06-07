package lol.audhd.farrdriverinfo

import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

data class FardriverData(
    val voltage: Float = 0f,
    val lineCurrent: Float = 0f,
    val power: Float = 0f,
    val rpm: Float = 0f,
    val rawRpm: Short = 0,
    val gear: Int = 0,
    val speed: Float = 0f,
    val controllerTemp: Int = 0,
    val motorTemp: Int = 0,
    val soc: Int = 0,
    val isRegenFromCurrent: Boolean = false,
    val odometerMiles: Double = 0.0,
    val tripMiles: Double = 0.0,
    val consumedAh: Double = 0.0
) {
    val ahPerMile: Double
        get() = if (tripMiles > 0.05) consumedAh / tripMiles else 0.0

    fun getEstimatedRange(totalAh: Float): Double {
        val eff = ahPerMile
        return if (eff > 0.1) (totalAh.toDouble() - consumedAh).coerceAtLeast(0.0) / eff else 0.0
    }
}

data class FardriverSettings(
    val wheelCircumferenceM: Float = 1.999f,
    val motorPolePairs: Int = 10,
    val speedMultiplier: Float = 1.0f,
    val batteryAh: Float = 20.0f
)

class FardriverRepository(private val context: Context) {

    private val sharedPrefs = context.getSharedPreferences("fardriver_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<FardriverSettings> = _settings

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private var bluetoothGatt: BluetoothGatt? = null

    private val _uiState = MutableStateFlow(
        FardriverData(
            odometerMiles = sharedPrefs.getFloat("odometer", 0f).toDouble(),
            tripMiles = sharedPrefs.getFloat("trip", 0f).toDouble(),
            consumedAh = sharedPrefs.getFloat("consumed_ah", 0f).toDouble()
        )
    )
    val uiState: StateFlow<FardriverData> = _uiState

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState: StateFlow<String> = _connectionState

    private val serviceUuid = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val characteristicUuid = UUID.fromString("0000ffec-0000-1000-8000-00805f9b34fb")
    private val clientCharacteristicConfigUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val flashReadAddr = intArrayOf(
        0xE2, 0xE8, 0xEE, 0xE4, 0x06, 0x0C, 0x12, 0xE2, 0xE8, 0xEE, 0x18, 0x1E, 0x24, 0x2A,
        0xE2, 0xE8, 0xEE, 0x30, 0x5D, 0x63, 0x69, 0xE2, 0xE8, 0xEE, 0x7C, 0x82, 0x88, 0x8E,
        0xE2, 0xE8, 0xEE, 0x94, 0x9A, 0xA0, 0xA6, 0xE2, 0xE8, 0xEE, 0xAC, 0xB2, 0xB8, 0xBE,
        0xE2, 0xE8, 0xEE, 0xC4, 0xCA, 0xD0, 0xE2, 0xE8, 0xEE, 0xD6, 0xDC, 0xF4, 0xFA,
    )

    private var lastPacketTime = 0L

    private fun loadSettings(): FardriverSettings {
        return FardriverSettings(
            wheelCircumferenceM = sharedPrefs.getFloat("wheel_circ", 1.999f),
            motorPolePairs = sharedPrefs.getInt("pole_pairs", 10),
            speedMultiplier = sharedPrefs.getFloat("speed_mult", 1.0f),
            batteryAh = sharedPrefs.getFloat("battery_ah", 20.0f)
        )
    }

    fun updateSettings(newSettings: FardriverSettings) {
        sharedPrefs.edit {
            putFloat("wheel_circ", newSettings.wheelCircumferenceM)
            putInt("pole_pairs", newSettings.motorPolePairs)
            putFloat("speed_mult", newSettings.speedMultiplier)
            putFloat("battery_ah", newSettings.batteryAh)
        }
        _settings.value = newSettings
    }

    fun resetTrip() {
        sharedPrefs.edit { 
            putFloat("trip", 0f)
            putFloat("consumed_ah", 0f)
        }
        _uiState.value = _uiState.value.copy(tripMiles = 0.0, consumedAh = 0.0)
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
    fun startScanning() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        _connectionState.value = "Scanning..."

        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @RequiresPermission(allOf = [android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN])
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult?) {
            result?.device?.let { device ->
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                _connectionState.value = "Connecting to ${device.name ?: "Controller"}..."
                bluetoothGatt = device.connectGatt(context, false, gattCallback)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = "Discovering Services..."
                gatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionState.value = "Disconnected"
                bluetoothGatt = null
                lastPacketTime = 0L
            }
        }

        @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = "Connected"
                val service = gatt?.getService(serviceUuid)
                val characteristic = service?.getCharacteristic(characteristicUuid)
                if (characteristic != null) {
                    gatt.setCharacteristicNotification(characteristic, true)

                    // Enable BLE Notifications via Descriptor
                    val descriptor = characteristic.getDescriptor(clientCharacteristicConfigUuid)
                    if (descriptor != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(descriptor)
                        }
                    }
                }
            }
        }

        // Deprecated fallback for older Android SDK targets, handles incoming array packets
        @Deprecated("Used for compatibility")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            @Suppress("DEPRECATION")
            characteristic?.value?.let { processPacket(it) }
        }

        // Standard callback for Android 13+ APIs
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            processPacket(value)
        }
    }

    private fun verifyCRC(data: ByteArray): Boolean {
        if (data.size != 16) return false
        var crc = 0x7F3C
        for (i in 0 until 14) {
            val byteVal = data[i].toInt() and 0xFF
            crc = crc xor byteVal
            repeat(8) {
                crc = if ((crc and 1) != 0) (crc shr 1) xor 0xA001 else crc shr 1
            }
        }
        val packetCrc = ((data[15].toInt() and 0xFF) shl 8) or (data[14].toInt() and 0xFF)
        return crc == packetCrc
    }

    private fun processPacket(packet: ByteArray) {
        if (!verifyCRC(packet)) return

        val id = packet[1].toInt() and 0x3F
        if (id >= flashReadAddr.size) return
        val address = flashReadAddr[id]

        // Offset payload by 2 bytes (skipping HEADER and ID frame)
        val pData = packet.copyOfRange(2, packet.size)
        var currentData = _uiState.value

        // Helper calculations to match Arduino's unsigned logic
        fun getUShort(b1: Byte, b2: Byte): Int = ((b2.toInt() and 0xFF) shl 8) or (b1.toInt() and 0xFF)
        fun getShort(b1: Byte, b2: Byte): Short = (((b2.toInt() and 0xFF) shl 8) or (b1.toInt() and 0xFF)).toShort()

        val currentTime = System.currentTimeMillis()
        val timeDeltaMs = if (lastPacketTime > 0) currentTime - lastPacketTime else 0
        lastPacketTime = currentTime

        when (address) {
            0xE2 -> {
                val gear = ((pData[0].toInt() shr 2) and 0x03) + 1
                val rawRpm = getShort(pData[6], pData[7])
                currentData = currentData.copy(gear = gear, rawRpm = rawRpm)
            }
            0xE8 -> {
                val newVoltage = getUShort(pData[0], pData[1]) / 10.0f
                var lineCurrent = currentData.lineCurrent
                var isRegen = currentData.isRegenFromCurrent

                if (newVoltage in 0f..100f) {
                    currentData = currentData.copy(voltage = newVoltage)
                }

                val newCurrent = getShort(pData[4], pData[5]) / 4.0f
                if (newCurrent in -100f..300f) {
                    lineCurrent = newCurrent
                    isRegen = newCurrent < 0f
                }
                currentData = currentData.copy(lineCurrent = lineCurrent, isRegenFromCurrent = isRegen)
            }
            0xD6 -> {
                val newControllerTemp = getShort(pData[10], pData[11]).toInt()
                if (newControllerTemp in -19..99) {
                    currentData = currentData.copy(controllerTemp = newControllerTemp)
                }
            }
            0xF4 -> {
                val newMotorTemp = getShort(pData[0], pData[1]).toInt()
                if (newMotorTemp in -19..199) {
                    currentData = currentData.copy(motorTemp = newMotorTemp)
                }
                val soc = pData[3].toInt() and 0xFF
                currentData = currentData.copy(soc = soc)
            }
        }

        // Secondary math transformations using configurable settings
        val settings = _settings.value
        val displayRawRpm = if (currentData.rawRpm < 0) 0 else currentData.rawRpm.toInt()

        val rpm = displayRawRpm * 4.0f / settings.motorPolePairs
        
        // Odometer calculation: distance = speed * time
        val speedMs = (rpm / 60.0f) * settings.wheelCircumferenceM
        val distanceMeters = speedMs * (timeDeltaMs / 1000.0f)
        val distanceMiles = distanceMeters / 1609.344
        
        val newOdo = currentData.odometerMiles + distanceMiles
        val newTrip = currentData.tripMiles + distanceMiles

        // Ah calculation: Ah = (Current * Time) / 3600
        // Use max(0, lineCurrent) to only count consumption if desired, 
        // but typically Ah/mi includes regen as negative consumption or net.
        // Let's stick to net Ah for efficiency.
        val consumedAhDelta = (currentData.lineCurrent * (timeDeltaMs / 1000.0f)) / 3600.0
        val newConsumedAh = currentData.consumedAh + consumedAhDelta

        // Persist values to avoid data loss, using a small threshold to avoid excessive writes
        if (Math.abs(newOdo - sharedPrefs.getFloat("odometer", 0f)) > 0.05) {
            sharedPrefs.edit { 
                putFloat("odometer", newOdo.toFloat())
                putFloat("trip", newTrip.toFloat())
                putFloat("consumed_ah", newConsumedAh.toFloat())
            }
        }

        val speedKmh = (rpm * settings.wheelCircumferenceM * 60.0f) / 1000.0f
        val speed = speedKmh * 0.621371f * settings.speedMultiplier
        val power = if (currentData.voltage > 0) currentData.voltage * currentData.lineCurrent else 0f

        _uiState.value = currentData.copy(
            rpm = rpm, 
            speed = speed, 
            power = power, 
            odometerMiles = newOdo,
            tripMiles = newTrip,
            consumedAh = newConsumedAh
        )
    }
}
