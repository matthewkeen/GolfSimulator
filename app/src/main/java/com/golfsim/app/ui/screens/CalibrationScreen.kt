package com.golfsim.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.golfsim.app.camera.CalibrationProfile
import com.golfsim.app.camera.GolfCameraManager
import com.golfsim.app.ui.GolfSimViewModel
import com.golfsim.app.ui.Screen
import com.golfsim.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*

enum class CalibrationStep(val title: String, val instruction: String, val icon: String) {
    WELCOME(
        "Camera Calibration",
        "This wizard will tune the ball detection for your exact lighting and setup. It takes about 60 seconds.",
        "🎯"
    ),
    POSITION_CAMERA(
        "Position Your Camera",
        "Mount your Pixel 7 on a stable surface. Point it at the tee from the side. The ball should be visible in the frame.",
        "📱"
    ),
    PLACE_BALL(
        "Place the Ball",
        "Put a white golf ball on the tee or ground. Tap the ball in the live preview to mark its location.",
        "⛳"
    ),
    BRIGHTNESS(
        "Adjust Brightness",
        "Tune the brightness threshold until only the ball is highlighted green — not lights or reflections.",
        "💡"
    ),
    SIZE(
        "Adjust Ball Size",
        "Set the min/max size so only the ball is detected. The green circle should match the ball exactly.",
        "⭕"
    ),
    DISTANCE(
        "Set Distance",
        "How far is the camera from the ball? This calibrates speed calculations.",
        "📏"
    ),
    TEST(
        "Test Detection",
        "Move the ball slightly. The tracker should follow it smoothly with a green ring.",
        "✅"
    ),
    COMPLETE(
        "Calibration Complete!",
        "Your settings have been saved. Ball detection is now optimised for your setup.",
        "🏆"
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(vm: GolfSimViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var currentStep by remember { mutableStateOf(CalibrationStep.WELCOME) }
    var profile by remember { mutableStateOf(vm.calibrationProfile.value) }

    val trackingState by vm.cameraManager.trackingState.collectAsState()
    val fps by vm.cameraManager.fps.collectAsState()
    val ballRadius by vm.cameraManager.ballRadius.collectAsState()
    val ballPositions by vm.cameraManager.detectedBallPositions.collectAsState()

    var manualTapPoint by remember { mutableStateOf<Offset?>(null) }
    var previewWidth by remember { mutableStateOf(1) }
    var previewHeight by remember { mutableStateOf(1) }

    var testFramesDetected by remember { mutableStateOf(0) }
    var testFramesTotal by remember { mutableStateOf(0) }
    var isTesting by remember { mutableStateOf(false) }

    val hasCameraPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    val stepIndex = CalibrationStep.values().indexOf(currentStep)
    val progressFraction = stepIndex.toFloat() / (CalibrationStep.values().size - 1).toFloat()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ball Calibration", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { vm.navigateTo(Screen.SETTINGS) }) {
                        Icon(Icons.Default.ArrowBack, null)
                    }
                },
                actions = {
                    Box(
                        modifier = Modifier
                            .background(Color.Black.copy(0.4f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "${fps.toInt()} fps",
                            fontSize = 12.sp,
                            color = if (fps >= 55) GolfGreenLight else Color(0xFFEF5350),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = DarkBackground
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // Progress bar - use Float overload compatible with the Compose version
            LinearProgressIndicator(
                progress = progressFraction,
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = GolfGreenLight,
                trackColor = Color(0xFF1A2E1A)
            )

            // Camera preview (from PLACE_BALL onwards)
            if (currentStep >= CalibrationStep.PLACE_BALL && hasCameraPermission) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {

                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).also { pv ->
                                pv.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                vm.cameraManager.startCamera(pv, lifecycleOwner)
                                vm.cameraManager.startCapturing()
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    if (currentStep == CalibrationStep.PLACE_BALL) {
                                        manualTapPoint = offset
                                        val normX = if (previewWidth > 0) offset.x / previewWidth else 0.5f
                                        val normY = if (previewHeight > 0) offset.y / previewHeight else 0.5f
                                        vm.cameraManager.setManualBallHint(normX, normY)
                                    }
                                }
                            }
                    )

                    // Tracking overlay
                    CalibrationOverlay(
                        trackingState = trackingState,
                        ballPositions = ballPositions,
                        manualTapPoint = manualTapPoint,
                        currentStep = currentStep,
                        profile = profile,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Detection quality badge
                    val quality = getDetectionQuality(trackingState)
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .background(Color.Black.copy(0.7f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).background(quality.color, CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(quality.label, fontSize = 12.sp, color = Color.White)
                        }
                    }

                    if (ballRadius > 0f) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp)
                                .background(Color.Black.copy(0.7f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("r=${ballRadius.toInt()}px", fontSize = 12.sp, color = GoldAccent)
                        }
                    }
                }
            } else if (currentStep < CalibrationStep.PLACE_BALL) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f).background(DarkSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Text(currentStep.icon, fontSize = 96.sp)
                }
            }

            // Bottom panel
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface)
                    .navigationBarsPadding()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(currentStep.title, fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color.White)
                Text(currentStep.instruction, fontSize = 14.sp, color = Color(0xFFB0BEC5), lineHeight = 20.sp)

                when (currentStep) {

                    CalibrationStep.BRIGHTNESS -> {
                        CalibrationSlider(
                            label = "Brightness Threshold",
                            value = profile.brightnessThreshold.toFloat(),
                            valueRange = 150f..245f,
                            steps = 18,
                            displayValue = "${profile.brightnessThreshold}",
                            description = "Lower = detects dimmer balls. Higher = only very bright objects.",
                            onValueChange = {
                                profile = profile.copy(brightnessThreshold = it.toInt())
                                vm.cameraManager.updateCalibration(profile)
                            }
                        )
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val auto = vm.cameraManager.autoDetectBrightness()
                                    profile = profile.copy(brightnessThreshold = auto)
                                    vm.cameraManager.updateCalibration(profile)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, GolfGreenLight)
                        ) {
                            Icon(Icons.Default.AutoAwesome, null, tint = GolfGreenLight)
                            Spacer(Modifier.width(8.dp))
                            Text("Auto-Detect", color = GolfGreenLight)
                        }
                    }

                    CalibrationStep.SIZE -> {
                        CalibrationSlider(
                            label = "Minimum Ball Size",
                            value = profile.minBallRadius.toFloat(),
                            valueRange = 2f..30f,
                            steps = 27,
                            displayValue = "${profile.minBallRadius}px",
                            description = "Ignore blobs smaller than this (filters noise/dust).",
                            onValueChange = {
                                profile = profile.copy(minBallRadius = it.toInt())
                                vm.cameraManager.updateCalibration(profile)
                            }
                        )
                        Spacer(Modifier.height(4.dp))
                        CalibrationSlider(
                            label = "Maximum Ball Size",
                            value = profile.maxBallRadius.toFloat(),
                            valueRange = 20f..150f,
                            steps = 65,
                            displayValue = "${profile.maxBallRadius}px",
                            description = "Ignore blobs larger than this (filters lights/reflections).",
                            onValueChange = {
                                profile = profile.copy(maxBallRadius = it.toInt())
                                vm.cameraManager.updateCalibration(profile)
                            }
                        )
                        Spacer(Modifier.height(4.dp))
                        CalibrationSlider(
                            label = "Circularity",
                            value = profile.circularityThreshold,
                            valueRange = 0.2f..0.95f,
                            steps = 74,
                            displayValue = String.format("%.2f", profile.circularityThreshold),
                            description = "How round the blob must be. Higher = stricter.",
                            onValueChange = {
                                profile = profile.copy(circularityThreshold = it)
                                vm.cameraManager.updateCalibration(profile)
                            }
                        )
                    }

                    CalibrationStep.DISTANCE -> {
                        CalibrationSlider(
                            label = "Camera Distance",
                            value = profile.cameraDistanceFeet.toFloat(),
                            valueRange = 3f..20f,
                            steps = 33,
                            displayValue = "${profile.cameraDistanceFeet} ft",
                            description = "Distance from camera lens to golf ball.",
                            onValueChange = {
                                profile = profile.copy(cameraDistanceFeet = it.toInt())
                                vm.cameraManager.updateCalibration(profile)
                            }
                        )
                        Spacer(Modifier.height(4.dp))
                        CalibrationSlider(
                            label = "Camera Height",
                            value = profile.cameraHeightInches.toFloat(),
                            valueRange = 0f..48f,
                            steps = 47,
                            displayValue = "${profile.cameraHeightInches}\"",
                            description = "Height of camera above ground in inches.",
                            onValueChange = {
                                profile = profile.copy(cameraHeightInches = it.toInt())
                                vm.cameraManager.updateCalibration(profile)
                            }
                        )
                        val yardsPerPx = computeYardsPerPixel(profile)
                        Card(colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant), shape = RoundedCornerShape(10.dp)) {
                            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Scale factor", color = Color(0xFF9E9E9E), fontSize = 13.sp)
                                Text("${String.format("%.4f", yardsPerPx)} yds/px", color = GoldAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }

                    CalibrationStep.TEST -> {
                        LaunchedEffect(isTesting) {
                            if (isTesting) {
                                testFramesDetected = 0
                                testFramesTotal = 0
                                repeat(120) {
                                    delay(16)
                                    testFramesTotal++
                                    if (trackingState is GolfCameraManager.TrackingState.BallDetected) testFramesDetected++
                                }
                                isTesting = false
                            }
                        }
                        val detectionRate = if (testFramesTotal > 0) testFramesDetected * 100 / testFramesTotal else 0
                        val rateColor = when {
                            detectionRate >= 80 -> GolfGreenLight
                            detectionRate >= 50 -> GoldAccent
                            else -> Color(0xFFEF5350)
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant), shape = RoundedCornerShape(12.dp)) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Detection Rate", color = Color.White, fontWeight = FontWeight.Bold)
                                    Text(
                                        if (testFramesTotal > 0) "$detectionRate%" else "—",
                                        color = rateColor, fontWeight = FontWeight.Black, fontSize = 20.sp
                                    )
                                }
                                if (testFramesTotal > 0) {
                                    Spacer(Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = detectionRate / 100f,
                                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                        color = rateColor,
                                        trackColor = Color(0xFF1A2E1A)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        when {
                                            detectionRate >= 80 -> "✅ Excellent! Ready to play."
                                            detectionRate >= 50 -> "⚠️ Acceptable. Try improving lighting."
                                            else -> "❌ Poor. Go back and adjust brightness/size."
                                        },
                                        fontSize = 13.sp, color = rateColor
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = { isTesting = true },
                            enabled = !isTesting,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SkyBlue),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isTesting) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Testing… (2s)")
                            } else {
                                Icon(Icons.Default.PlayArrow, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Run Detection Test")
                            }
                        }
                    }

                    CalibrationStep.COMPLETE -> CalibrationSummary(profile)

                    else -> {}
                }

                Spacer(Modifier.height(4.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (currentStep != CalibrationStep.WELCOME) {
                        OutlinedButton(
                            onClick = { currentStep = CalibrationStep.values()[stepIndex - 1] },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, Color.Gray)
                        ) {
                            Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                            Spacer(Modifier.width(4.dp))
                            Text("Back", color = Color.White)
                        }
                    }
                    Button(
                        onClick = {
                            if (currentStep == CalibrationStep.COMPLETE) {
                                vm.saveCalibration(profile)
                                vm.navigateTo(Screen.SETTINGS)
                            } else {
                                if (stepIndex == CalibrationStep.values().size - 2) {
                                    vm.saveCalibration(profile)
                                }
                                currentStep = CalibrationStep.values()[stepIndex + 1]
                            }
                        },
                        modifier = Modifier
                            .weight(if (currentStep == CalibrationStep.WELCOME) 1f else 2f)
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (currentStep == CalibrationStep.COMPLETE) GoldAccent else GolfGreenLight
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            when (currentStep) {
                                CalibrationStep.WELCOME -> "Start Calibration"
                                CalibrationStep.COMPLETE -> "Done — Start Playing"
                                else -> "Next"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = if (currentStep == CalibrationStep.COMPLETE) Color.Black else Color.White
                        )
                        if (currentStep != CalibrationStep.WELCOME && currentStep != CalibrationStep.COMPLETE) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.ArrowForward, null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CalibrationOverlay(
    trackingState: GolfCameraManager.TrackingState,
    ballPositions: List<com.golfsim.app.models.BallPosition>,
    manualTapPoint: Offset?,
    currentStep: CalibrationStep,
    profile: CalibrationProfile,
    modifier: Modifier = Modifier
) {
    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulseAnim.animateFloat(
        initialValue = 1f, targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "scale"
    )

    Canvas(modifier = modifier) {
        val cx = size.width / 2
        val cy = size.height / 2
        drawLine(Color.White.copy(0.15f), Offset(cx, 0f), Offset(cx, size.height), 1f)
        drawLine(Color.White.copy(0.15f), Offset(0f, cy), Offset(size.width, cy), 1f)

        if (ballPositions.size > 1) {
            val recent = ballPositions.takeLast(30)
            recent.forEachIndexed { i, pos ->
                val alpha = i.toFloat() / recent.size * 0.7f
                drawCircle(color = GolfGreenLight.copy(alpha = alpha), radius = 5.dp.toPx(), center = Offset(pos.x, pos.y))
            }
        }

        manualTapPoint?.let { tap ->
            drawCircle(color = GoldAccent.copy(0.4f), radius = 40.dp.toPx(), center = tap, style = Stroke(1.5.dp.toPx()))
            drawLine(GoldAccent.copy(0.6f), Offset(tap.x - 15.dp.toPx(), tap.y), Offset(tap.x + 15.dp.toPx(), tap.y), 1.5.dp.toPx())
            drawLine(GoldAccent.copy(0.6f), Offset(tap.x, tap.y - 15.dp.toPx()), Offset(tap.x, tap.y + 15.dp.toPx()), 1.5.dp.toPx())
        }

        when (val state = trackingState) {
            is GolfCameraManager.TrackingState.BallDetected -> {
                val center = Offset(state.x, state.y)
                val r = state.radius
                val ringColor = when {
                    state.confidence > 0.75f -> GolfGreenLight
                    state.confidence > 0.5f -> GoldAccent
                    else -> Color(0xFFEF5350)
                }
                drawCircle(color = ringColor.copy(0.3f), radius = r * pulseScale + 12.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
                drawCircle(color = ringColor, radius = r + 8.dp.toPx(), center = center, style = Stroke(2.5.dp.toPx()))
                drawCircle(ringColor, 4.dp.toPx(), center)

                if (currentStep == CalibrationStep.SIZE) {
                    drawCircle(color = Color.Red.copy(0.5f), radius = profile.minBallRadius.toFloat() + 4.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
                    drawCircle(color = SkyBlue.copy(0.5f), radius = profile.maxBallRadius.toFloat() + 4.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
                }
            }
            else -> {
                drawArc(
                    color = Color.White.copy(0.3f), startAngle = 0f, sweepAngle = 270f, useCenter = false,
                    topLeft = Offset(cx - 30.dp.toPx(), cy - 30.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(60.dp.toPx(), 60.dp.toPx()),
                    style = Stroke(2.dp.toPx())
                )
            }
        }
    }
}

@Composable
fun CalibrationSlider(
    label: String, value: Float, valueRange: ClosedFloatingPointRange<Float>,
    steps: Int, displayValue: String, description: String, onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            Box(modifier = Modifier.background(GolfGreenDark, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 3.dp)) {
                Text(displayValue, fontSize = 13.sp, color = GolfGreenLight, fontWeight = FontWeight.Bold)
            }
        }
        Slider(
            value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps,
            colors = SliderDefaults.colors(thumbColor = GolfGreenLight, activeTrackColor = GolfGreenLight, inactiveTrackColor = Color(0xFF1A2E1A))
        )
        Text(description, fontSize = 11.sp, color = Color(0xFF9E9E9E), lineHeight = 16.sp)
    }
}

@Composable
fun CalibrationSummary(profile: CalibrationProfile) {
    Card(colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant), shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Saved Settings", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
            Divider(color = Color(0xFF2E4A2E))
            SummaryRow("Brightness threshold", "${profile.brightnessThreshold}")
            SummaryRow("Min ball radius", "${profile.minBallRadius} px")
            SummaryRow("Max ball radius", "${profile.maxBallRadius} px")
            SummaryRow("Circularity", String.format("%.2f", profile.circularityThreshold))
            SummaryRow("Camera distance", "${profile.cameraDistanceFeet} ft")
            SummaryRow("Camera height", "${profile.cameraHeightInches}\"")
            SummaryRow("Scale factor", "${String.format("%.4f", computeYardsPerPixel(profile))} yds/px")
        }
    }
}

@Composable
fun SummaryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = Color(0xFF9E9E9E))
        Text(value, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

data class DetectionQuality(val label: String, val color: Color)

fun getDetectionQuality(state: GolfCameraManager.TrackingState): DetectionQuality = when (state) {
    is GolfCameraManager.TrackingState.BallDetected -> when {
        state.confidence > 0.75f -> DetectionQuality("Ball Locked ✓", Color(0xFF66BB6A))
        state.confidence > 0.5f -> DetectionQuality("Ball Detected", GoldAccent)
        else -> DetectionQuality("Low Confidence", Color(0xFFEF5350))
    }
    is GolfCameraManager.TrackingState.WaitingForBall -> DetectionQuality("Scanning…", Color(0xFF9E9E9E))
    is GolfCameraManager.TrackingState.SwingInProgress -> DetectionQuality("Swing!", GolfGreenLight)
    else -> DetectionQuality("Idle", Color(0xFF9E9E9E))
}

fun computeYardsPerPixel(profile: CalibrationProfile): Double {
    val halfFovRad = Math.toRadians(77.0 / 2)
    val distanceYards = profile.cameraDistanceFeet / 3.0
    val realWidthYards = 2 * distanceYards * tan(halfFovRad)
    return realWidthYards / 1080.0
}
