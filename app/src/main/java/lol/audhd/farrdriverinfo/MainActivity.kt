package lol.audhd.farrdriverinfo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val repository by lazy { FardriverRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Requests standard runtime permissions automatically on initialization
        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions.values.all { it }) {
                repository.startScanning()
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MainContent(repository)
            }
        }

        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (requiredPermissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            repository.startScanning()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }
}

@Composable
fun MainContent(repository: FardriverRepository) {
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(repository, onBack = { showSettings = false })
    } else {
        DashboardScreen(repository, onOpenSettings = { showSettings = true })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(repository: FardriverRepository, onOpenSettings: () -> Unit) {
    val uiState by repository.uiState.collectAsState()
    val status by repository.connectionState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fardriver Dashboard", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Vertical SOC Gauge (Tall box from top to bottom)
            VerticalBatteryGauge(soc = uiState.soc, voltage = uiState.voltage, modifier = Modifier.width(70.dp).fillMaxHeight())

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Connection Status Bar
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (status == "Connected") Color(0xFF1B5E20) else Color(0xFFB71C1C)
                    )
                ) {
                    Text(
                        text = "Status: $status",
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }

                // Voltage Readout
                DashboardCard(
                    label = "Battery Voltage",
                    value = String.format(Locale.US, "%.1f V", uiState.voltage),
                    modifier = Modifier.fillMaxWidth()
                )

                // Current Readout
                DashboardCard(
                    label = "Current",
                    value = String.format(Locale.US, "%.1f A", uiState.lineCurrent),
                    modifier = Modifier.fillMaxWidth()
                )

                // Power & Gear Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    DashboardCard(
                        label = "Power",
                        value = String.format(Locale.US, "%.0f W", uiState.power),
                        modifier = Modifier.weight(0.7f)
                    )
                    
                    val gearColor = when (uiState.gear) {
                        1 -> Color(0xFF2E7D32) // Green
                        2 -> Color(0xFF1565C0) // Blue
                        3 -> Color(0xFFFBC02D) // Yellow
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    val gearContentColor = if (uiState.gear == 3) Color.Black else Color.White

                    DashboardCard(
                        label = "Gear",
                        value = uiState.gear.toString(),
                        containerColor = gearColor,
                        contentColor = gearContentColor,
                        modifier = Modifier.weight(0.3f)
                    )
                }

                // Secondary Telemetry & Temp Gauges Section
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Telemetry Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        DashboardCard("Speed (MPH)", String.format(Locale.US, "%.0f", uiState.speed), modifier = Modifier.weight(1f))
                        DashboardCard("RPM", String.format(Locale.US, "%.0f", uiState.rpm), modifier = Modifier.weight(1f))
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        DashboardCard("Trip", String.format(Locale.US, "%.1f", uiState.tripMiles), modifier = Modifier.weight(1f))
                        DashboardCard("ODO", String.format(Locale.US, "%.1f", uiState.odometerMiles), modifier = Modifier.weight(1f))
                    }

                    // Temp Gauges Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        TempGauge(label = "Controller", tempC = uiState.controllerTemp, modifier = Modifier.weight(1f))
                        TempGauge(label = "Motor", tempC = uiState.motorTemp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repository: FardriverRepository, onBack: () -> Unit) {
    val settings by repository.settings.collectAsState()
    
    var wheelCirc by remember(settings) { mutableStateOf(settings.wheelCircumferenceM.toString()) }
    var polePairs by remember(settings) { mutableStateOf(settings.motorPolePairs.toString()) }
    var speedMult by remember(settings) { mutableStateOf(settings.speedMultiplier.toString()) }

    var showTripResetDialog by remember { mutableStateOf(false) }

    if (showTripResetDialog) {
        AlertDialog(
            onDismissRequest = { showTripResetDialog = false },
            title = { Text("Reset Trip Meter") },
            text = { Text("Are you sure you want to reset the trip meter to 0.0 miles?") },
            confirmButton = {
                TextButton(onClick = {
                    repository.resetTrip()
                    showTripResetDialog = false
                }) { Text("Reset", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showTripResetDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuration Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                "Motor & Wheel Config",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            OutlinedTextField(
                value = wheelCirc,
                onValueChange = { wheelCirc = it },
                label = { Text("Wheel Circumference (meters)") },
                placeholder = { Text("e.g. 1.999") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("The rolling circumference of your tire in meters") }
            )

            OutlinedTextField(
                value = polePairs,
                onValueChange = { polePairs = it },
                label = { Text("Motor Pole Pairs") },
                placeholder = { Text("e.g. 10") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Number of magnetic pole pairs in the motor") }
            )

            HorizontalDivider()

            Text(
                "Calibration",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            OutlinedTextField(
                value = speedMult,
                onValueChange = { speedMult = it },
                label = { Text("Speed Multiplier") },
                placeholder = { Text("1.0 for default") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Adjust this to calibrate against GPS speed") }
            )

            HorizontalDivider()

            Text(
                "Trip Meter",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            
            Button(
                onClick = { showTripResetDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
            ) {
                Text("Reset Trip Meter")
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    val newSettings = FardriverSettings(
                        wheelCircumferenceM = wheelCirc.toFloatOrNull() ?: settings.wheelCircumferenceM,
                        motorPolePairs = polePairs.toIntOrNull() ?: settings.motorPolePairs,
                        speedMultiplier = speedMult.toFloatOrNull() ?: settings.speedMultiplier
                    )
                    repository.updateSettings(newSettings)
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Apply & Save Settings", modifier = Modifier.padding(8.dp))
            }
        }
    }
}

@Composable
fun VerticalBatteryGauge(soc: Int, voltage: Float, modifier: Modifier = Modifier) {
    val gaugeColor = when {
        soc >= 50 -> androidx.compose.ui.graphics.lerp(
            Color(0xFFFFC107), // Amber
            Color(0xFF4CAF50), // Green
            (soc - 50) / 50f
        )
        else -> androidx.compose.ui.graphics.lerp(
            Color(0xFFF44336), // Red
            Color(0xFFFFC107), // Amber
            soc / 50f
        )
    }

    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "SOC",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Gray.copy(alpha = 0.2f))
            ) {
                // Filled level from bottom
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(soc.coerceIn(0, 100) / 100f)
                        .align(Alignment.BottomCenter)
                        .background(gaugeColor)
                )
                
                // Percentage Text overlay
                Text(
                    text = "$soc%",
                    modifier = Modifier.align(Alignment.Center),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    color = Color.White
                )
            }

            Text(
                text = String.format(Locale.US, "%.1fV", voltage),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun TempGauge(label: String, tempC: Int, modifier: Modifier = Modifier) {
    val tempF = (tempC * 9 / 5) + 32
    val color = when {
        tempF > 248 -> Color(0xFFD32F2F) // Red (>120C)
        tempF > 194 -> Color(0xFFFBC02D)  // Yellow (>90C)
        else -> Color(0xFF388E3C)       // Green
    }
    val contentColor = if (tempF in 195..248) Color.Black else Color.White
    
    DashboardCard(
        label = "$label\nTemp",
        value = "${tempF}°F",
        containerColor = color,
        contentColor = contentColor,
        modifier = modifier
    )
}

@Composable
fun DashboardCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label.uppercase(Locale.US),
                fontSize = 16.sp,
                color = contentColor.copy(alpha = 0.7f),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
        }
    }
}
