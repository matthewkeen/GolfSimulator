package com.golfsim.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.golfsim.app.camera.CalibrationProfile
import com.golfsim.app.camera.GolfCameraManager
import com.golfsim.app.ui.GolfSimViewModel
import com.golfsim.app.ui.Screen
import com.golfsim.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*

// ─── Calibration state machine ───────────────────────────────────────────────

enum class CalibState {
    /** Camera live, waiting for user to tap the ball */
    TAP_TO_SELECT,
    /** User tapped — running auto-lock for 1.5 s */
    LOCKING,
    /** Ball locked and tracking confirmed */
    LOCKED,
    /** Optional: advanced fine-tune sliders */
    ADVANCED,
    /** Final summary before saving */
    COMPLETE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(vm: GolfSimViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // ── state ────────────────────────────────────────────────────────────────
    var calibState by remember { mutableStateOf(CalibState.TAP_TO_SELECT) }
    var profile by remember { mutableStateOf(vm.calibrationProfile.value) }

    // Where the user tapped (screen pixels)
    var tapPoint by remember { mutableStateOf<Offset?>(null) }
    // Sampled luma at tap point (0-255), set during LOCKING phase
    var sampledLuma by remember { mutableStateOf(0) }
    // Sampled ball radius in pixels
    var sampledRadius by remember { mutableStateOf(0f) }

    var previewW by remember { mutableStateOf(1) }
    var previewH by remember { mutableStateOf(1) }
    var showAdvanced by remember { mutableStateOf(false) }

    val trackingState by vm.cameraManager.trackingState.collectAsState()
    val fps by vm.cameraManager.fps.collectAsState()
    val ballRadius by vm.cameraManager.ballRadius.collectAsState()
    val ballPositions by vm.cameraManager.detectedBallPositions.collectAsState()

    val hasCameraPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    // Locking animation
    val lockProgress = remember { Animatable(0f) }
    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulseAnim.animateFloat(
        1f, 1.25f,
        infiniteRepeatable(tween(500, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "pulse"
    )

    // ── auto-advance: once locked, show tracking for 1 s then allow save ────
    LaunchedEffect(calibState) {
        if (calibState == CalibState.LOCKING) {
            lockProgress.snapTo(0f)
            lockProgress.animateTo(1f, animationSpec = tween(1800))
            if (calibState == CalibState.LOCKING) calibState = CalibState.LOCKED
        }
    }

    // ── Helper: run auto-calibration from a tap point ────────────────────────
    fun autoCalibrate(tapOffset: Offset) {
        tapPoint = tapOffset
        calibState = CalibState.LOCKING

        val normX = if (previewW > 0) tapOffset.x / previewW else 0.5f
        val normY = if (previewH > 0) tapOffset.y / previewH else 0.5f

        // Tell camera manager to bias detection around tap point
        vm.cameraManager.setManualBallHint(normX, normY)

        scope.launch {
            // Wait a couple frames for first detection reading
            delay(300)
            val detectedR = ballRadius.coerceAtLeast(4f)
            sampledRadius = detectedR

            // Auto-set min/max radius around detected size
            val minR = (detectedR * 0.5f).toInt().coerceAtLeast(3)
            val maxR = (detectedR * 2.2f).toInt().coerceAtMost(200)

            // Auto-brightness: sample luma around tap in the current frame
            // We use detected tracking state confidence as a proxy
            val brightness = when (val state = trackingState) {
                is GolfCameraManager.TrackingState.BallDetected -> {
                    // confidence high → ball is bright; adjust threshold
                    val estimatedLuma = 180 + (state.confidence * 60).toInt()
                    estimatedLuma.coerceIn(150, 240)
                }
                else -> 200
            }
            sampledLuma = brightness

            profile = profile.copy(
                brightnessThreshold = brightness,
                minBallRadius       = minR,
                maxBallRadius       = maxR,
                manualHintX         = normX,
                manualHintY         = normY,
                useManualHint       = true
            )
            vm.cameraManager.updateCalibration(profile)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (calibState) {
                            CalibState.TAP_TO_SELECT -> "Tap the Ball"
                            CalibState.LOCKING       -> "Locking On…"
                            CalibState.LOCKED        -> "Ball Locked ✓"
                            CalibState.ADVANCED      -> "Fine-Tune"
                            CalibState.COMPLETE      -> "All Done!"
                        },
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { vm.navigateTo(Screen.SETTINGS) }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // FPS badge
                    if (hasCameraPermission) {
                        Box(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .background(Color.Black.copy(0.5f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "${fps.toInt()} fps",
                                fontSize = 12.sp,
                                color = if (fps >= 55) GolfGreenLight else Color(0xFFEF5350),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = DarkBackground
    ) { padding ->

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // ── Camera preview (always full-width) ────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (hasCameraPermission) {
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
                            .pointerInput(calibState) {
                                detectTapGestures { offset ->
                                    // Tap is always active — tap again to re-select
                                    if (calibState != CalibState.COMPLETE) {
                                        autoCalibrate(offset)
                                    }
                                }
                            }
                            .onGloballyPositioned { coords ->
                                previewW = coords.size.width
                                previewH = coords.size.height
                            }
                    )

                    // ── Overlay drawn on top of the preview ──────────────
                    CalibOverlay(
                        calibState    = calibState,
                        tapPoint      = tapPoint,
                        trackingState = trackingState,
                        ballPositions = ballPositions,
                        lockProgress  = lockProgress.value,
                        pulseScale    = pulseScale,
                        modifier      = Modifier.fillMaxSize()
                    )

                    // ── "Tap the ball" instruction bubble ──────────────────
                    AnimatedVisibility(
                        visible = calibState == CalibState.TAP_TO_SELECT,
                        enter   = fadeIn() + slideInVertically { it / 2 },
                        exit    = fadeOut(),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
                    ) {
                        TapInstructionBubble()
                    }

                    // ── Locked badge ───────────────────────────────────────
                    AnimatedVisibility(
                        visible = calibState == CalibState.LOCKED || calibState == CalibState.COMPLETE,
                        enter   = scaleIn() + fadeIn(),
                        exit    = fadeOut(),
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                    ) {
                        LockedBadge()
                    }

                    // ── Locking progress arc (top-right) ──────────────────
                    AnimatedVisibility(
                        visible = calibState == CalibState.LOCKING,
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                    ) {
                        CircularProgressIndicator(
                            progress = lockProgress.value,
                            color = GoldAccent,
                            trackColor = Color.White.copy(0.2f),
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                } else {
                    // No permission
                    Box(Modifier.fillMaxSize().background(DarkSurface), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.CameraAlt, null, tint = Color(0xFF9E9E9E), modifier = Modifier.size(64.dp))
                            Text("Camera permission required", color = Color(0xFF9E9E9E), textAlign = TextAlign.Center)
                        }
                    }
                }
            }

            // ── Bottom panel ─────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface)
                    .navigationBarsPadding()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (calibState) {

                    // ── Waiting for tap ───────────────────────────────────
                    CalibState.TAP_TO_SELECT -> {
                        Text(
                            "Point the camera at the golf ball, then tap it on screen.",
                            fontSize = 15.sp, color = Color.White, lineHeight = 22.sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.TouchApp, null, tint = GoldAccent, modifier = Modifier.size(18.dp))
                            Text(
                                "One tap is all it takes — calibration is automatic.",
                                fontSize = 13.sp, color = GoldAccent
                            )
                        }
                        Divider(color = Color(0xFF2E4A2E))
                        SetupTipsRow()
                    }

                    // ── Locking ───────────────────────────────────────────
                    CalibState.LOCKING -> {
                        Text("Locking on to the ball…", fontSize = 15.sp, color = Color.White)
                        LinearProgressIndicator(
                            progress = lockProgress.value,
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = GolfGreenLight,
                            trackColor = Color(0xFF1A2E1A)
                        )
                        Text(
                            "Hold still — analysing brightness, size & position.",
                            fontSize = 13.sp, color = Color(0xFF9E9E9E)
                        )
                        OutlinedButton(
                            onClick = {
                                calibState = CalibState.TAP_TO_SELECT
                                tapPoint   = null
                                vm.cameraManager.resetTracking()
                            },
                            border = BorderStroke(1.dp, Color.Gray),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Tap a different spot", color = Color.White)
                        }
                    }

                    // ── Locked ────────────────────────────────────────────
                    CalibState.LOCKED -> {
                        // Show what was auto-detected
                        AutoDetectedStats(
                            brightness = sampledLuma,
                            radiusPx   = sampledRadius,
                            detectionRate = (ballPositions.size.toFloat() / 120f * 100).coerceIn(0f, 100f),
                            trackingState = trackingState
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = {
                                    calibState = CalibState.TAP_TO_SELECT
                                    tapPoint   = null
                                    vm.cameraManager.resetTracking()
                                },
                                border  = BorderStroke(1.dp, Color.Gray),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Re-tap")
                            }
                            OutlinedButton(
                                onClick = { showAdvanced = !showAdvanced },
                                border  = BorderStroke(1.dp, Color(0xFF4CAF50).copy(0.5f)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Tune, null, tint = GolfGreenLight, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Advanced", color = GolfGreenLight)
                            }
                        }

                        // Advanced tweaks (collapsible)
                        AnimatedVisibility(visible = showAdvanced) {
                            AdvancedSliders(
                                profile = profile,
                                onProfileChange = { updated ->
                                    profile = updated
                                    vm.cameraManager.updateCalibration(updated)
                                }
                            )
                        }

                        // Distance setting (always shown — needed for speed accuracy)
                        DistanceSetting(profile = profile, onProfileChange = { updated ->
                            profile = updated
                            vm.cameraManager.updateCalibration(updated)
                        })

                        // Save button
                        Button(
                            onClick = {
                                vm.saveCalibration(profile)
                                calibState = CalibState.COMPLETE
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GolfGreenLight),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Check, null, tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("Save & Start Tracking", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    CalibState.ADVANCED -> { /* handled via showAdvanced flag above */ }

                    // ── Complete ──────────────────────────────────────────
                    CalibState.COMPLETE -> {
                        CalibCompleteSummary(profile = profile)
                        Button(
                            onClick = { vm.navigateTo(Screen.SETTINGS) },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GoldAccent),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Done — Start Playing", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }
                }
            }
        }
    }
}

// ─── Overlay ─────────────────────────────────────────────────────────────────

@Composable
fun CalibOverlay(
    calibState: CalibState,
    tapPoint: Offset?,
    trackingState: GolfCameraManager.TrackingState,
    ballPositions: List<com.golfsim.app.models.BallPosition>,
    lockProgress: Float,
    pulseScale: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width; val h = size.height

        // Subtle crosshair when waiting
        if (calibState == CalibState.TAP_TO_SELECT) {
            drawLine(Color.White.copy(0.12f), Offset(w / 2, 0f), Offset(w / 2, h), 1f)
            drawLine(Color.White.copy(0.12f), Offset(0f, h / 2), Offset(w, h / 2), 1f)
        }

        // Ball trail (positions history)
        if (ballPositions.size > 1 &&
            (calibState == CalibState.LOCKING || calibState == CalibState.LOCKED || calibState == CalibState.COMPLETE)
        ) {
            ballPositions.takeLast(40).forEachIndexed { i, pos ->
                drawCircle(
                    color = GolfGreenLight.copy(alpha = i.toFloat() / 40f * 0.6f),
                    radius = 5.dp.toPx(),
                    center = Offset(pos.x, pos.y)
                )
            }
        }

        // Tap point indicator
        tapPoint?.let { tap ->
            when (calibState) {
                CalibState.TAP_TO_SELECT -> { /* no marker shown pre-tap */ }

                CalibState.LOCKING -> {
                    // Spinning gold ring while locking
                    drawCircle(
                        color = GoldAccent.copy(lockProgress * 0.5f + 0.2f),
                        radius = 44.dp.toPx(),
                        center = tap,
                        style = Stroke(2.dp.toPx())
                    )
                    drawCircle(GoldAccent.copy(0.9f), 5.dp.toPx(), tap)
                    // Arc showing progress
                    drawArc(
                        color = GoldAccent,
                        startAngle = -90f,
                        sweepAngle = lockProgress * 360f,
                        useCenter = false,
                        topLeft = Offset(tap.x - 44.dp.toPx(), tap.y - 44.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(88.dp.toPx(), 88.dp.toPx()),
                        style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                CalibState.LOCKED, CalibState.COMPLETE -> {
                    // Live tracking ring on the actual detected position
                }

                else -> {}
            }
        }

        // Live detection ring (overrides tap ring once ball is confirmed)
        when (val state = trackingState) {
            is GolfCameraManager.TrackingState.BallDetected -> {
                val center = Offset(state.x, state.y)
                val r = state.radius.coerceAtLeast(10f)
                val ringColor = when {
                    state.confidence > 0.75f -> GolfGreenLight
                    state.confidence > 0.5f  -> GoldAccent
                    else                     -> Color(0xFFEF5350)
                }
                // Pulsing outer halo
                drawCircle(ringColor.copy(0.2f), (r + 18.dp.toPx()) * pulseScale, center, style = Stroke(1.dp.toPx()))
                // Solid ring
                drawCircle(ringColor, r + 8.dp.toPx(), center, style = Stroke(2.5.dp.toPx()))
                // Centre dot
                drawCircle(ringColor, 5.dp.toPx(), center)
                // Corner tick marks
                val tickLen = 10.dp.toPx()
                val rr = r + 8.dp.toPx()
                for (angle in listOf(45f, 135f, 225f, 315f)) {
                    val rad = Math.toRadians(angle.toDouble())
                    val cx = center.x + rr * cos(rad).toFloat()
                    val cy = center.y + rr * sin(rad).toFloat()
                    val ex = center.x + (rr + tickLen) * cos(rad).toFloat()
                    val ey = center.y + (rr + tickLen) * sin(rad).toFloat()
                    drawLine(ringColor, Offset(cx, cy), Offset(ex, ey), 2.dp.toPx(), StrokeCap.Round)
                }
            }
            else -> {}
        }
    }
}

// ─── Tap instruction bubble ──────────────────────────────────────────────────

@Composable
fun TapInstructionBubble() {
    val bounce = rememberInfiniteTransition(label = "bounce")
    val offsetY by bounce.animateFloat(
        0f, -8f,
        infiniteRepeatable(tween(800, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "bounce"
    )
    Box(
        modifier = Modifier
            .offset(y = offsetY.dp)
            .background(Color.Black.copy(0.75f), RoundedCornerShape(24.dp))
            .border(1.dp, GoldAccent.copy(0.6f), RoundedCornerShape(24.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("👆", fontSize = 20.sp)
            Text("Tap the golf ball to calibrate", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ─── Locked badge ────────────────────────────────────────────────────────────

@Composable
fun LockedBadge() {
    Row(
        modifier = Modifier
            .background(GolfGreenLight.copy(0.9f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(Icons.Default.MyLocation, null, tint = Color.White, modifier = Modifier.size(14.dp))
        Text("Ball Locked", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

// ─── Auto-detected stats card ─────────────────────────────────────────────────

@Composable
fun AutoDetectedStats(
    brightness: Int,
    radiusPx: Float,
    detectionRate: Float,
    trackingState: GolfCameraManager.TrackingState
) {
    val confidence = when (val s = trackingState) {
        is GolfCameraManager.TrackingState.BallDetected -> s.confidence
        else -> 0f
    }
    val qualityColor = when {
        confidence > 0.75f  -> GolfGreenLight
        confidence > 0.5f   -> GoldAccent
        else                -> Color(0xFFEF5350)
    }
    val qualityLabel = when {
        confidence > 0.75f  -> "Excellent ✓"
        confidence > 0.5f   -> "Good"
        else                -> "Weak — try better lighting"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Auto-Calibration Results", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(qualityLabel, color = qualityColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Divider(color = Color(0xFF2E4A2E))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                AutoStatItem("Brightness", "$brightness", GoldAccent)
                AutoStatItem("Ball Size", "${radiusPx.toInt()} px", SkyBlue)
                AutoStatItem("Detection", "${detectionRate.toInt()}%", qualityColor)
            }
            // Quality bar
            LinearProgressIndicator(
                progress = confidence,
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                color = qualityColor,
                trackColor = Color(0xFF1A2E1A)
            )
        }
    }
}

@Composable
fun AutoStatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.Black)
        Text(label, color = Color(0xFF9E9E9E), fontSize = 11.sp)
    }
}

// ─── Distance setting ─────────────────────────────────────────────────────────

@Composable
fun DistanceSetting(profile: CalibrationProfile, onProfileChange: (CalibrationProfile) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Straighten, null, tint = GolfGreenLight, modifier = Modifier.size(16.dp))
                Text("Camera Distance", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .background(GolfGreenDark, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text("${profile.cameraDistanceFeet} ft", color = GolfGreenLight, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text("How far is the camera from the ball? Affects speed accuracy.", color = Color(0xFF9E9E9E), fontSize = 12.sp)
            Slider(
                value = profile.cameraDistanceFeet.toFloat(),
                onValueChange = { onProfileChange(profile.copy(cameraDistanceFeet = it.toInt())) },
                valueRange = 3f..20f,
                steps = 33,
                colors = SliderDefaults.colors(thumbColor = GolfGreenLight, activeTrackColor = GolfGreenLight)
            )
        }
    }
}

// ─── Advanced sliders ─────────────────────────────────────────────────────────

@Composable
fun AdvancedSliders(profile: CalibrationProfile, onProfileChange: (CalibrationProfile) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        border = BorderStroke(1.dp, Color(0xFF2E4A2E)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Advanced Settings", color = Color(0xFF9E9E9E), fontSize = 12.sp, letterSpacing = 1.sp)

            CalibrationSlider(
                label       = "Brightness Threshold",
                value       = profile.brightnessThreshold.toFloat(),
                valueRange  = 140f..245f,
                steps       = 20,
                displayValue = "${profile.brightnessThreshold}",
                description = "Minimum pixel brightness to count as ball. Lower = more sensitive.",
                onValueChange = { onProfileChange(profile.copy(brightnessThreshold = it.toInt())) }
            )

            CalibrationSlider(
                label       = "Min Ball Radius",
                value       = profile.minBallRadius.toFloat(),
                valueRange  = 2f..40f,
                steps       = 37,
                displayValue = "${profile.minBallRadius} px",
                description = "Smallest blob that counts as the ball.",
                onValueChange = { onProfileChange(profile.copy(minBallRadius = it.toInt())) }
            )

            CalibrationSlider(
                label       = "Max Ball Radius",
                value       = profile.maxBallRadius.toFloat(),
                valueRange  = 20f..200f,
                steps       = 35,
                displayValue = "${profile.maxBallRadius} px",
                description = "Largest blob that counts as the ball.",
                onValueChange = { onProfileChange(profile.copy(maxBallRadius = it.toInt())) }
            )

            CalibrationSlider(
                label       = "Circularity",
                value       = profile.circularityThreshold,
                valueRange  = 0.2f..0.95f,
                steps       = 74,
                displayValue = String.format("%.2f", profile.circularityThreshold),
                description = "How round a blob must be. Higher = stricter.",
                onValueChange = { onProfileChange(profile.copy(circularityThreshold = it)) }
            )
        }
    }
}

// ─── Setup tips row ──────────────────────────────────────────────────────────

@Composable
fun SetupTipsRow() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SetupTip("💡", "Good lighting makes detection much more reliable")
        SetupTip("⚪", "Use a white golf ball for best contrast")
        SetupTip("📐", "Position camera 8–12 ft from the tee, side-on")
    }
}

// ─── Completion summary ──────────────────────────────────────────────────────

@Composable
fun CalibCompleteSummary(profile: CalibrationProfile) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🏆", fontSize = 24.sp)
                Text("Calibration Saved", color = GolfGreenLight, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Divider(color = Color(0xFF2E4A2E))
            SummaryRow("Brightness threshold", "${profile.brightnessThreshold}")
            SummaryRow("Ball size range", "${profile.minBallRadius} – ${profile.maxBallRadius} px")
            SummaryRow("Camera distance", "${profile.cameraDistanceFeet} ft")
            SummaryRow("Scale", "${String.format("%.4f", computeYardsPerPixel(profile))} yds/px")
        }
    }
}

// ─── Shared composables ──────────────────────────────────────────────────────

@Composable
fun CalibrationSlider(
    label: String, value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int, displayValue: String, description: String,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            Box(modifier = Modifier.background(GolfGreenDark, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(displayValue, fontSize = 12.sp, color = GolfGreenLight, fontWeight = FontWeight.Bold)
            }
        }
        Slider(
            value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps,
            colors = SliderDefaults.colors(thumbColor = GolfGreenLight, activeTrackColor = GolfGreenLight, inactiveTrackColor = Color(0xFF2E4A2E))
        )
        Text(description, fontSize = 11.sp, color = Color(0xFF9E9E9E))
    }
}

@Composable
fun SummaryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = Color(0xFF9E9E9E))
        Text(value, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SetupTip(icon: String, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(icon, fontSize = 15.sp)
        Text(text, fontSize = 13.sp, color = Color(0xFFB0BEC5))
    }
}

fun computeYardsPerPixel(profile: CalibrationProfile): Double {
    val halfFovRad = Math.toRadians(77.0 / 2)
    val distanceYards = profile.cameraDistanceFeet / 3.0
    val realWidthYards = 2 * distanceYards * tan(halfFovRad)
    return realWidthYards / 1080.0
}

// Helper — used by CalibOverlay to determine detection quality
fun getDetectionQuality(state: GolfCameraManager.TrackingState): Pair<String, Color> = when (state) {
    is GolfCameraManager.TrackingState.BallDetected -> when {
        state.confidence > 0.75f -> "Ball Locked ✓" to GolfGreenLight
        state.confidence > 0.5f  -> "Ball Detected" to GoldAccent
        else                     -> "Low Confidence" to Color(0xFFEF5350)
    }
    is GolfCameraManager.TrackingState.WaitingForBall  -> "Scanning…" to Color(0xFF9E9E9E)
    is GolfCameraManager.TrackingState.SwingInProgress -> "Swing!" to GolfGreenLight
    else -> "Idle" to Color(0xFF9E9E9E)
}

// Missing import helper — onGloballyPositioned needs this
private fun Modifier.onGloballyPositioned(block: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit): Modifier =
    this.then(androidx.compose.ui.layout.onGloballyPositioned(block))
