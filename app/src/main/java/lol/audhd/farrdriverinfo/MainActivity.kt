package lol.audhd.farrdriverinfo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.sin
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val repository by lazy { FardriverRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Requests standard runtime permissions automatically on initialization
        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
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
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
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
    var screenState by remember { mutableStateOf("dashboard") }

    when (screenState) {
        "settings" -> SettingsScreen(repository) { screenState = "dashboard" }
        "quad" -> QuadDashboardScreen(
            repository,
            onOpenSettings = { screenState = "settings" },
            onOpenErrors = { screenState = "errors" }
        ) { screenState = "dashboard" }
        "errors" -> ErrorScreen(repository) { screenState = "dashboard" }
        else -> DashboardScreen(
            repository,
            onOpenSettings = { screenState = "settings" },
            onOpenErrors = { screenState = "errors" }
        ) { screenState = "quad" }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    repository: FardriverRepository, 
    onOpenSettings: () -> Unit,
    onOpenErrors: () -> Unit,
    onSwitchDashboard: () -> Unit
) {
    val uiState by repository.uiState.collectAsState()
    val settings by repository.settings.collectAsState()

    var showTripResetDialog by remember { mutableStateOf(value = false) }

    if (showTripResetDialog) {
        AlertDialog(
            onDismissRequest = { showTripResetDialog = false },
            title = { Text("Reset Trip Meter") },
            text = { Text("Are you sure you want to reset the trip, used Ah, and range estimation?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        repository.resetTrip()
                        showTripResetDialog = false
                    },
                ) { Text("Reset", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showTripResetDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fardriver Dashboard", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                actions = {
                    IconButton(onClick = onOpenErrors) {
                        val uiState by repository.uiState.collectAsState()
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Errors",
                            tint = if (uiState.activeErrors.isNotEmpty()) Color.Red else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = onSwitchDashboard) {
                        Icon(Icons.Default.Dashboard, contentDescription = "Switch Layout")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Large Digital Speed Readout (Replaces Sweep Gauge)
            DashboardCard(
                label = "Speed",
                value = String.format(Locale.US, "%.0f", uiState.speed),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.0f), // Reduced further to 1.0f
                containerColor = Color.Transparent,
                contentColor = Color.White,
                valueFontSize = 80.sp, // Reduced to 80.sp to ensure bottom visibility
                labelFontSize = 16.sp
            )
            
            // Brake Status Indicator (Overlaid)
            if (uiState.brakeActive) {
                Card(
                    modifier = Modifier.align(Alignment.End).padding(end = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Red)
                ) {
                    Text(
                        "BRAKE",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp
                    )
                }
            }

            Row(
                modifier = Modifier.weight(2.8f).fillMaxWidth(), // Increased from 2.5f to give bottom more space
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Vertical SOC Gauge (Increased Height)
                Column(
                    modifier = Modifier.width(70.dp).fillMaxHeight()
                ) {
                    Spacer(modifier = Modifier.weight(0.5f)) // Smaller spacer at top
                    VerticalBatteryGauge(
                        soc = uiState.soc,
                        voltage = uiState.voltage,
                        modifier = Modifier.weight(2.5f).fillMaxWidth() // Larger gauge
                    )
                }

                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Battery & Power Group
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        DashboardCard(
                            label = "Voltage",
                            value = String.format(Locale.US, "%.1f V", uiState.voltage),
                            modifier = Modifier.weight(1f),
                            valueFontSize = 24.sp,
                            labelFontSize = 12.sp
                        )
                        DashboardCard(
                            label = "Current",
                            value = String.format(Locale.US, "%.1f A", uiState.lineCurrent),
                            modifier = Modifier.weight(1f),
                            valueFontSize = 24.sp,
                            labelFontSize = 12.sp
                        )
                    }

                    // Power & Gear Group
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        DashboardCard(
                            label = "Power",
                            value = String.format(Locale.US, "%.0f W", uiState.power),
                            modifier = Modifier.weight(0.7f),
                            valueFontSize = 24.sp,
                            labelFontSize = 12.sp
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
                            modifier = Modifier.weight(0.3f),
                            valueFontSize = 24.sp,
                            labelFontSize = 12.sp
                        )
                    }

                    // Stats Box
                    DashboardCard(
                        label = "System Stats",
                        value = "",
                        modifier = Modifier.fillMaxWidth().weight(2f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp),
                            verticalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                StatItem("Ah/Mi", String.format(Locale.US, "%.2f", uiState.ahPerMile))
                                StatItem("Used", String.format(Locale.US, "%.1fAh", uiState.consumedAh))
                                StatItem("Range", String.format(Locale.US, "%.1fmi", uiState.getEstimatedRange(settings.batteryAh)))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                StatItem("Trip", String.format(Locale.US, "%.1fmi", uiState.tripMiles))
                                StatItem("ODO", String.format(Locale.US, "%.1fmi", uiState.odometerMiles))
                                StatItem("Ph A", String.format(Locale.US, "%.1fA", uiState.phaseACurrent))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val ctrlColor = if (uiState.controllerTemp > 90) Color.Red else if (uiState.controllerTemp > 70) Color.Yellow else Color.Unspecified
                                val motorColor = if (uiState.motorTemp > 120) Color.Red else if (uiState.motorTemp > 90) Color.Yellow else Color.Unspecified
                                StatItem("Ctrl", "${(((uiState.controllerTemp * 9) / 5)) + 32}°F", color = ctrlColor)

                                // Reset Button - Centered between temps
                                Button(
                                    onClick = { showTripResetDialog = true },
                                    modifier = Modifier.height(24.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
                                ) {
                                    Text("RESET", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }

                                StatItem("Motor", "${(((uiState.motorTemp * 9) / 5)) + 32}°F", color = motorColor)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuadDashboardScreen(
    repository: FardriverRepository,
    onOpenSettings: () -> Unit,
    onOpenErrors: () -> Unit,
    onSwitchDashboard: () -> Unit
) {
    val uiState by repository.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Retro Analog View", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                actions = {
                    IconButton(onClick = onOpenErrors) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Errors",
                            tint = if (uiState.activeErrors.isNotEmpty()) Color.Red else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = onSwitchDashboard) {
                        Icon(Icons.Default.Speed, contentDescription = "Switch Layout")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Centered Main Auto Meter Gauge (Speed + Digital Voltage)
            Box(
                modifier = Modifier
                    .weight(1.8f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                AutoMeterGauge(
                    speed = uiState.speed,
                    amps = uiState.lineCurrent,
                    voltage = uiState.voltage,
                    modifier = Modifier.size(360.dp)
                )

                // Brake Status Indicator (Overlaid)
                if (uiState.brakeActive) {
                    Card(
                        modifier = Modifier.align(Alignment.TopEnd),
                        colors = CardDefaults.cardColors(containerColor = Color.Red)
                    ) {
                        Text(
                            "BRAKE",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // Two Smaller Temp Gauges in a Row
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmallRetroGauge(
                    label = "MCU TEMP",
                    value = ((uiState.controllerTemp * 9f) / 5f) + 32f,
                    minValue = 60f,
                    maxValue = 180f,
                    unit = "°F",
                    modifier = Modifier.weight(1f).aspectRatio(1f)
                )
                SmallRetroGauge(
                    label = "MOTOR TEMP",
                    value = ((uiState.motorTemp * 9f) / 5f) + 32f,
                    minValue = 60f,
                    maxValue = 180f,
                    unit = "°F",
                    modifier = Modifier.weight(1f).aspectRatio(1f)
                )
            }

            // Bottom Section: Full Width SOC
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Phase A: ${String.format(Locale.US, "%.1fA", uiState.phaseACurrent)}", style = MaterialTheme.typography.labelSmall)
                    Text("Phase C: ${String.format(Locale.US, "%.1fA", uiState.phaseCCurrent)}", style = MaterialTheme.typography.labelSmall)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("SOC", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text("${uiState.soc}%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Gray.copy(alpha = 0.2f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(uiState.soc / 100f)
                            .fillMaxHeight()
                            .background(
                                when {
                                    uiState.soc > 50 -> Color(0xFF4CAF50)
                                    uiState.soc > 20 -> Color(0xFFFFC107)
                                    else -> Color(0xFFF44336)
                                }
                            )
                    )
                }
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String, color: Color = Color.Unspecified) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label.uppercase(Locale.US),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.6f),
        )
        Text(
            value,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            color = if (color == Color.Unspecified) Color.White else color,
        )
    }
}

@Composable
fun AutoMeterGauge(
    speed: Float,
    amps: Float,
    voltage: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val center = size.center
        val radius = size.minDimension / 2
        
        // 1. Polished Chrome Bezel
        val bezelOuter = radius
        val bezelInner = radius * 0.92f
        
        drawCircle(
            brush = Brush.sweepGradient(
                colors = listOf(Color(0xFF888888), Color(0xFFEEEEEE), Color(0xFF888888), Color(0xFF444444), Color(0xFF888888))
            ),
            radius = bezelOuter,
            center = center
        )
        drawCircle(
            color = Color.White,
            radius = bezelInner,
            center = center
        )

        // 2. Dial Face (White)
        drawCircle(
            color = Color.White,
            radius = bezelInner * 0.98f,
            center = center
        )

        // 3. MAIN SPEEDOMETER (0-50 MPH)
        val speedStartAngle = 135f
        val speedSweepAngle = 270f
        val maxSpeed = 50f
        
        // Ticks and Numbers
        for (i in 0..50) {
            val angle = speedStartAngle + (i / maxSpeed) * speedSweepAngle
            val angleRad = Math.toRadians(angle.toDouble())
            val isMajor = i % 10 == 0
            val isMinor = i % 5 == 0
            
            val tickLen = when {
                isMajor -> radius * 0.12f
                isMinor -> radius * 0.08f
                else -> radius * 0.04f
            }
            
            val start = Offset(
                x = center.x + (bezelInner * 0.95f - tickLen) * cos(angleRad).toFloat(),
                y = center.y + (bezelInner * 0.95f - tickLen) * sin(angleRad).toFloat()
            )
            val end = Offset(
                x = center.x + (bezelInner * 0.95f) * cos(angleRad).toFloat(),
                y = center.y + (bezelInner * 0.95f) * sin(angleRad).toFloat()
            )
            
            drawLine(
                color = Color.Black,
                start = start,
                end = end,
                strokeWidth = if (isMajor) 3.dp.toPx() else 1.dp.toPx()
            )

            if (isMajor) {
                val textRadius = bezelInner * 0.72f
                val tx = center.x + textRadius * cos(angleRad).toFloat()
                val ty = center.y + textRadius * sin(angleRad).toFloat()
                
                drawContext.canvas.nativeCanvas.drawText(
                    i.toString(),
                    tx,
                    ty + 8.dp.toPx(),
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.BLACK
                        textSize = 24.sp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
            }
        }

        // Branding
        drawContext.canvas.nativeCanvas.drawText(
            "MPH",
            center.x,
            center.y - radius * 0.5f,
            android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 14.sp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            }
        )

        // 4. SUB-GAUGE AMPS (Analog Nested) - Range 0 to 60
        val subCenter = Offset(center.x, center.y + radius * 0.45f)
        val subRadius = radius * 0.35f
        val subStartAngle = 150f
        val subSweepAngle = 240f
        val minAmps = 0f
        val maxAmps = 60f

        // Amps Ticks
        for (a in 0..60 step 5) {
            val aAngle = subStartAngle + ((a - minAmps) / (maxAmps - minAmps)) * subSweepAngle
            val aAngleRad = Math.toRadians(aAngle.toDouble())
            val isMajorA = a % 10 == 0
            
            val aTickLen = if (isMajorA) subRadius * 0.2f else subRadius * 0.1f
            val aStart = Offset(
                x = subCenter.x + (subRadius - aTickLen) * cos(aAngleRad).toFloat(),
                y = subCenter.y + (subRadius - aTickLen) * sin(aAngleRad).toFloat()
            )
            val aEnd = Offset(
                x = subCenter.x + subRadius * cos(aAngleRad).toFloat(),
                y = subCenter.y + subRadius * sin(aAngleRad).toFloat()
            )
            
            drawLine(color = Color.Black, start = aStart, end = aEnd, strokeWidth = 1.dp.toPx())
            
            if (isMajorA) {
                val aTextRadius = subRadius * 0.65f
                val atx = subCenter.x + aTextRadius * cos(aAngleRad).toFloat()
                val aty = subCenter.y + aTextRadius * sin(aAngleRad).toFloat()
                drawContext.canvas.nativeCanvas.drawText(
                    a.toString(),
                    atx,
                    aty + 4.dp.toPx(),
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.BLACK
                        textSize = 10.sp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                )
            }
        }

        drawContext.canvas.nativeCanvas.drawText(
            "AMPS",
            subCenter.x,
            subCenter.y + subRadius * 0.3f,
            android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 8.sp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
            }
        )

        // 5. LCD DISPLAY (Digital Voltage Readout)
        val lcdWidth = radius * 0.35f
        val lcdHeight = radius * 0.12f
        val lcdTop = center.y - radius * 0.35f // Moved up significantly to avoid bezel
        val lcdLeft = center.x - lcdWidth / 2
        
        // LCD Background
        drawRoundRect(
            color = Color(0xFF9EA78D),
            topLeft = Offset(lcdLeft, lcdTop),
            size = Size(lcdWidth, lcdHeight),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        )
        // Subtle LCD bezel
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.3f),
            topLeft = Offset(lcdLeft, lcdTop),
            size = Size(lcdWidth, lcdHeight),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            style = Stroke(width = 1.dp.toPx())
        )

        // Digital Voltage Readout in LCD
        drawContext.canvas.nativeCanvas.drawText(
            String.format(Locale.US, "%.1f V", voltage),
            center.x,
            lcdTop + lcdHeight * 0.75f,
            android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 12.sp.toPx() 
                typeface = android.graphics.Typeface.MONOSPACE
                textAlign = android.graphics.Paint.Align.CENTER
                isFakeBoldText = true
            }
        )

        // 6. NEEDLES
        // Amps Needle (Small Analog)
        val aCurrentAngle = subStartAngle + ((amps.coerceIn(minAmps, maxAmps) - minAmps) / (maxAmps - minAmps)) * subSweepAngle
        val aNeedleRad = Math.toRadians(aCurrentAngle.toDouble())
        drawLine(
            color = Color(0xFFFF4500),
            start = subCenter,
            end = Offset(
                x = subCenter.x + (subRadius * 0.85f) * cos(aNeedleRad).toFloat(),
                y = subCenter.y + (subRadius * 0.85f) * sin(aNeedleRad).toFloat()
            ),
            strokeWidth = 2.5.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(color = Color.Black, radius = 4.dp.toPx(), center = subCenter)

        // Speed Needle (Large)
        val sCurrentAngle = speedStartAngle + (speed.coerceIn(0f, maxSpeed) / maxSpeed) * speedSweepAngle
        val sNeedleRad = Math.toRadians(sCurrentAngle.toDouble())
        val sNeedleEnd = Offset(
            x = center.x + (bezelInner * 0.9f) * cos(sNeedleRad).toFloat(),
            y = center.y + (bezelInner * 0.9f) * sin(sNeedleRad).toFloat()
        )
        val sNeedleTail = Offset(
            x = center.x - (bezelInner * 0.2f) * cos(sNeedleRad).toFloat(),
            y = center.y - (bezelInner * 0.2f) * sin(sNeedleRad).toFloat()
        )
        
        drawLine(
            color = Color(0xFFFF4500),
            start = sNeedleTail,
            end = sNeedleEnd,
            strokeWidth = 6.dp.toPx(),
            cap = StrokeCap.Round
        )
        
        // Needle Hub (Black)
        drawCircle(color = Color.Black, radius = 12.dp.toPx(), center = center)
        drawCircle(color = Color.DarkGray, radius = 4.dp.toPx(), center = center)
    }
}

@Composable
fun SmallRetroGauge(
    label: String,
    value: Float,
    minValue: Float,
    maxValue: Float,
    unit: String,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val center = size.center
        val radius = size.minDimension / 2
        
        // 1. Chrome Bezel
        drawCircle(
            brush = Brush.sweepGradient(
                colors = listOf(Color(0xFF888888), Color(0xFFEEEEEE), Color(0xFF888888), Color(0xFF444444), Color(0xFF888888))
            ),
            radius = radius,
            center = center
        )
        drawCircle(color = Color.White, radius = radius * 0.92f, center = center)

        // 2. Face
        val faceRadius = radius * 0.9f
        drawCircle(color = Color.White, radius = faceRadius, center = center)

        // 3. Scale
        val startAngle = 150f
        val sweepAngle = 240f
        
        for (i in 0..10) {
            val ratio = i / 10f
            val angle = startAngle + ratio * sweepAngle
            val angleRad = Math.toRadians(angle.toDouble())
            
            val isMajor = i % 5 == 0
            val tickLen = if (isMajor) radius * 0.15f else radius * 0.08f
            
            val start = Offset(
                x = center.x + (faceRadius - tickLen) * cos(angleRad).toFloat(),
                y = center.y + (faceRadius - tickLen) * sin(angleRad).toFloat()
            )
            val end = Offset(
                x = center.x + faceRadius * cos(angleRad).toFloat(),
                y = center.y + faceRadius * sin(angleRad).toFloat()
            )
            
            drawLine(color = Color.Black, start = start, end = end, strokeWidth = 1.dp.toPx())
            
            if (isMajor) {
                val scaleVal = minValue + ratio * (maxValue - minValue)
                val textRadius = faceRadius * 0.65f
                val tx = center.x + textRadius * cos(angleRad).toFloat()
                val ty = center.y + textRadius * sin(angleRad).toFloat()
                
                drawContext.canvas.nativeCanvas.drawText(
                    scaleVal.toInt().toString(),
                    tx,
                    ty + 4.dp.toPx(),
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.BLACK
                        textSize = 10.sp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
            }
        }

        // Labels
        drawContext.canvas.nativeCanvas.drawText(
            label,
            center.x,
            center.y - radius * 0.35f,
            android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 8.sp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
        )
        drawContext.canvas.nativeCanvas.drawText(
            String.format(Locale.US, "%.0f%s", value, unit),
            center.x,
            center.y + radius * 0.7f, // Moved lower
            android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 12.sp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.MONOSPACE
            }
        )

        // 4. Needle
        val currentAngle = startAngle + ((value.coerceIn(minValue, maxValue) - minValue) / (maxValue - minValue)) * sweepAngle
        val needleRad = Math.toRadians(currentAngle.toDouble())
        val needleEnd = Offset(
            x = center.x + (faceRadius * 0.85f) * cos(needleRad).toFloat(),
            y = center.y + (faceRadius * 0.85f) * sin(needleRad).toFloat()
        )
        
        drawLine(
            color = Color(0xFFFF4500),
            start = center,
            end = needleEnd,
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )
        
        drawCircle(color = Color.Black, radius = 5.dp.toPx(), center = center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repository: FardriverRepository, onBack: () -> Unit) {
    val settings by repository.settings.collectAsState()
    val uiState by repository.uiState.collectAsState()
    
    var wheelCirc by remember(settings) { mutableStateOf(settings.wheelCircumferenceM.toString()) }
    var polePairs by remember(settings) { mutableStateOf(settings.motorPolePairs.toString()) }
    var speedMult by remember(settings) { mutableStateOf(settings.speedMultiplier.toString()) }
    var batteryAh by remember(settings) { mutableStateOf(settings.batteryAh.toString()) }
    var odoInput by remember { mutableStateOf(uiState.odometerMiles.toString()) }

    var showTripResetDialog by remember { mutableStateOf(value = false) }
    var showOdoConfirmDialog by remember { mutableStateOf(value = false) }

    if (showTripResetDialog) {
        AlertDialog(
            onDismissRequest = { showTripResetDialog = false },
            title = { Text("Reset Trip Meter") },
            text = { Text("Are you sure you want to reset the trip meter to 0.0 miles?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        repository.resetTrip()
                        showTripResetDialog = false
                    },
                ) { Text("Reset", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showTripResetDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showOdoConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showOdoConfirmDialog = false },
            title = { Text("Set Odometer") },
            text = { Text("Are you sure you want to set the total odometer to $odoInput miles?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        repository.setOdometer(odoInput.toDoubleOrNull() ?: uiState.odometerMiles)
                        showOdoConfirmDialog = false
                    },
                ) { Text("Set ODO", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showOdoConfirmDialog = false }) { Text("Cancel") }
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

            OutlinedTextField(
                value = batteryAh,
                onValueChange = { batteryAh = it },
                label = { Text("Battery Capacity (Ah)") },
                placeholder = { Text("e.g. 20.0") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Total Amp-hours of your battery pack") }
            )

            HorizontalDivider()

            Text(
                "Maintenance",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = odoInput,
                    onValueChange = { odoInput = it },
                    label = { Text("Odometer (miles)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { showOdoConfirmDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Set ODO")
                }
            }

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
                        speedMultiplier = speedMult.toFloatOrNull() ?: settings.speedMultiplier,
                        batteryAh = batteryAh.toFloatOrNull() ?: settings.batteryAh
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
                textAlign = TextAlign.Center,
                color = Color.White
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ErrorScreen(repository: FardriverRepository, onBack: () -> Unit) {
    val uiState by repository.uiState.collectAsState()
    val status by repository.connectionState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("System Diagnostics") },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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

            Text(
                text = "Active Errors",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error
            )

            if (uiState.activeErrors.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20).copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.Dashboard, contentDescription = null, tint = Color(0xFF4CAF50))
                        Text("System healthy. No active faults detected.", fontWeight = FontWeight.Medium)
                    }
                }
            } else {
                uiState.activeErrors.forEach { error ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text(text = error, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "System Status",
                style = MaterialTheme.typography.titleMedium
            )

            StatusInfoRow("Brake Signal", if (uiState.brakeActive) "ACTIVE" else "Inactive", if (uiState.brakeActive) Color.Red else Color.Unspecified)
            StatusInfoRow("Throttle Error", if (uiState.throttleError) "FAULT" else "OK", if (uiState.throttleError) Color.Red else Color.Unspecified)
            StatusInfoRow("Hall Sensors", if (uiState.motorHallError) "FAULT" else "OK", if (uiState.motorHallError) Color.Red else Color.Unspecified)
            StatusInfoRow("MCU Temp", "${uiState.controllerTemp}°C", if (uiState.controllerTempProtect) Color.Red else Color.Unspecified)
            StatusInfoRow("Motor Temp", "${uiState.motorTemp}°C", if (uiState.motorTempProtect) Color.Red else Color.Unspecified)
            StatusInfoRow("Voltage Status", if (uiState.voltageProtect) "PROTECT" else "OK", if (uiState.voltageProtect) Color.Red else Color.Unspecified)
        }
    }
}

@Composable
fun StatusInfoRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

@Composable
fun DashboardCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = Color.White, // Pure white font
    valueFontSize: androidx.compose.ui.unit.TextUnit = 32.sp,
    labelFontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 4.dp, horizontal = 8.dp)
                .fillMaxSize(), // Fill entire card area
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center // Center content vertically
        ) {
            if (content != null) {
                content()
            } else {
                Text(
                    text = label.uppercase(Locale.US),
                    fontSize = labelFontSize,
                    color = contentColor.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = labelFontSize
                )
                Text(
                    text = value,
                    fontSize = valueFontSize,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    lineHeight = valueFontSize
                )
            }
        }
    }
}
