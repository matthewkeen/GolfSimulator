package com.golfsim.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.golfsim.app.models.*
import com.golfsim.app.ui.GolfSimViewModel
import com.golfsim.app.ui.Screen
import com.golfsim.app.ui.theme.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun SimulatorScreen(vm: GolfSimViewModel) {
    val context      = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val selectedClub   by vm.selectedClub.collectAsState()
    val trackingState  by vm.cameraManager.trackingState.collectAsState()
    val isReadyToShoot by vm.isReadyToShoot.collectAsState()
    val shotInProgress by vm.shotInProgress.collectAsState()
    val showShotResult by vm.showShotResult.collectAsState()
    val lastResult     by vm.lastShotResult.collectAsState()
    val lastMetrics    by vm.lastSwingMetrics.collectAsState()
    val ballPos        by vm.ballPositionOnScreen.collectAsState()
    val fps            by vm.cameraManager.fps.collectAsState()
    val ballHistory    by vm.cameraManager.detectedBallPositions.collectAsState()

    val hasCameraPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // ── Camera Preview ─────────────────────────────────────────────────
        if (hasCameraPermission) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { previewView ->
                        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        vm.cameraManager.startCamera(previewView, lifecycleOwner)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            NoCameraPermissionOverlay()
        }

        // ── Launch-Monitor Tracking Overlay ────────────────────────────────
        LaunchMonitorTrackingOverlay(
            ballHistory  = ballHistory,
            trackingState = trackingState,
            currentBallPos = ballPos,
            fps = fps
        )

        // ── Top HUD ────────────────────────────────────────────────────────
        TopHUD(
            club         = selectedClub,
            fps          = fps,
            trackingState = trackingState.toString().substringAfterLast("."),
            onBack       = { vm.navigateTo(Screen.HOME) }
        )

        // ── Bottom Controls ────────────────────────────────────────────────
        BottomControls(
            selectedClub   = selectedClub,
            isReady        = isReadyToShoot,
            shotInProgress = shotInProgress,
            onShoot        = { vm.startSwingCapture() },
            onSimulate     = { vm.simulateShotManually() },
            onClubSelect   = { vm.navigateTo(Screen.CLUB_SELECT) },
            modifier       = Modifier.align(Alignment.BottomCenter)
        )

        if (shotInProgress) ShotInProgressOverlay()

        if (showShotResult && lastResult != null && lastMetrics != null) {
            ShotResultSheet(
                result    = lastResult!!,
                metrics   = lastMetrics!!,
                club      = selectedClub,
                onDismiss = { vm.dismissShotResult() }
            )
        }
    }
}

// ─── Launch Monitor Tracking Overlay ─────────────────────────────────────────
// Mimics professional launch monitor display: trajectory trail + targeting ring
// with confidence colour, ball speed readout, and scan lines when searching.

@Composable
fun BoxScope.LaunchMonitorTrackingOverlay(
    ballHistory:   List<com.golfsim.app.models.BallPosition>,
    trackingState: com.golfsim.app.camera.GolfCameraManager.TrackingState,
    currentBallPos: Pair<Float, Float>?,
    fps: Float
) {
    // Pulsing animation for the search ring
    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(700, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "pulseScale"
    )
    // Rotation for scan arc when searching
    val scanAngle by pulse.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "scan"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {

        // ── Ball trajectory trail (ghost dots fade out) ──────────────────
        val trail = ballHistory.takeLast(40)
        if (trail.size > 1) {
            for (i in 1 until trail.size) {
                val alpha = (i.toFloat() / trail.size.toFloat()).pow(1.5f)
                val radius = 5.dp.toPx() * (0.4f + alpha * 0.6f)
                drawCircle(
                    color  = Color(0xFFFFEB3B).copy(alpha = alpha * 0.85f),
                    radius = radius,
                    center = Offset(trail[i].x, trail[i].y)
                )
            }
            // Connect trail with a fading path
            val pathColor = Color(0xFFFFEB3B)
            for (i in 1 until trail.size) {
                val alpha = (i.toFloat() / trail.size.toFloat()).pow(2f) * 0.5f
                drawLine(
                    color       = pathColor.copy(alpha = alpha),
                    start       = Offset(trail[i - 1].x, trail[i - 1].y),
                    end         = Offset(trail[i].x, trail[i].y),
                    strokeWidth = 2.dp.toPx(),
                    cap         = StrokeCap.Round
                )
            }
        }

        // ── Ball detection ring ──────────────────────────────────────────
        when (val state = trackingState) {
            is com.golfsim.app.camera.GolfCameraManager.TrackingState.BallDetected -> {
                val centre = Offset(state.x, state.y)
                val r      = state.radius.coerceAtLeast(12f)

                // Ring colour: green = high confidence, amber = medium, red = low
                val ringColor = when {
                    state.confidence > 0.70f -> Color(0xFF00E676)   // bright green
                    state.confidence > 0.45f -> Color(0xFFFFD600)   // amber
                    else                     -> Color(0xFFFF5252)   // red
                }

                // Outer halo (pulsing)
                drawCircle(
                    ringColor.copy(alpha = 0.15f * pulseScale),
                    (r + 22.dp.toPx()) * pulseScale, centre,
                    style = Stroke(1.5.dp.toPx())
                )
                // Main targeting ring
                drawCircle(ringColor, r + 9.dp.toPx(), centre, style = Stroke(2.5.dp.toPx()))
                // Inner fill (subtle)
                drawCircle(ringColor.copy(alpha = 0.08f), r, centre)
                // Centre crosshair dot
                drawCircle(ringColor, 4.dp.toPx(), centre)

                // Corner tick marks at 45° intervals
                val tickLen  = 10.dp.toPx()
                val ringRad  = r + 9.dp.toPx()
                for (angleDeg in listOf(45f, 135f, 225f, 315f)) {
                    val rad = Math.toRadians(angleDeg.toDouble())
                    val sx = centre.x + ringRad * cos(rad).toFloat()
                    val sy = centre.y + ringRad * sin(rad).toFloat()
                    val ex = centre.x + (ringRad + tickLen) * cos(rad).toFloat()
                    val ey = centre.y + (ringRad + tickLen) * sin(rad).toFloat()
                    drawLine(ringColor, Offset(sx, sy), Offset(ex, ey), 2.dp.toPx(), StrokeCap.Round)
                }

                // Confidence bar (arc overlay)
                val sweepAngle = state.confidence * 360f
                drawArc(
                    color      = ringColor.copy(alpha = 0.6f),
                    startAngle = -90f,
                    sweepAngle = sweepAngle,
                    useCenter  = false,
                    topLeft    = Offset(centre.x - r - 9.dp.toPx(), centre.y - r - 9.dp.toPx()),
                    size       = androidx.compose.ui.geometry.Size((r + 9.dp.toPx()) * 2f, (r + 9.dp.toPx()) * 2f),
                    style      = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            is com.golfsim.app.camera.GolfCameraManager.TrackingState.WaitingForBall -> {
                // Draw rotating scan arc in center region
                val cx = size.width / 2f; val cy = size.height / 2f
                val scanR = 80.dp.toPx()
                drawArc(
                    color      = Color(0xFF00E676).copy(alpha = 0.5f),
                    startAngle = scanAngle,
                    sweepAngle = 120f,
                    useCenter  = false,
                    topLeft    = Offset(cx - scanR, cy - scanR),
                    size       = androidx.compose.ui.geometry.Size(scanR * 2, scanR * 2),
                    style      = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                )
                // Corner brackets to show scan area
                val margin = 60.dp.toPx()
                val bracketLen = 24.dp.toPx()
                val bracketColor = Color(0xFF00E676).copy(alpha = 0.4f)
                val bStroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                // Top-left
                drawLine(bracketColor, Offset(margin, margin), Offset(margin + bracketLen, margin), 2.dp.toPx())
                drawLine(bracketColor, Offset(margin, margin), Offset(margin, margin + bracketLen), 2.dp.toPx())
                // Top-right
                drawLine(bracketColor, Offset(size.width - margin, margin), Offset(size.width - margin - bracketLen, margin), 2.dp.toPx())
                drawLine(bracketColor, Offset(size.width - margin, margin), Offset(size.width - margin, margin + bracketLen), 2.dp.toPx())
                // Bottom-left
                drawLine(bracketColor, Offset(margin, size.height - margin), Offset(margin + bracketLen, size.height - margin), 2.dp.toPx())
                drawLine(bracketColor, Offset(margin, size.height - margin), Offset(margin, size.height - margin - bracketLen), 2.dp.toPx())
                // Bottom-right
                drawLine(bracketColor, Offset(size.width - margin, size.height - margin), Offset(size.width - margin - bracketLen, size.height - margin), 2.dp.toPx())
                drawLine(bracketColor, Offset(size.width - margin, size.height - margin), Offset(size.width - margin, size.height - margin - bracketLen), 2.dp.toPx())
            }

            is com.golfsim.app.camera.GolfCameraManager.TrackingState.SwingInProgress -> {
                // Flash red borders
                drawRect(Color(0xFFFF5252).copy(alpha = 0.25f * pulseScale), style = Stroke(6.dp.toPx()))
            }

            else -> {}
        }
    }
}

// ─── Top HUD ─────────────────────────────────────────────────────────────────

@Composable
fun BoxScope.TopHUD(club: ClubType, fps: Float, trackingState: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.TopCenter)
            .background(Color.Black.copy(alpha = 0.55f))
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Text("←", fontSize = 22.sp, color = Color.White)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(club.displayName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = GoldAccent)
            Text(trackingState, fontSize = 10.sp, color = Color(0xFF9E9E9E))
        }

        // FPS + lock indicator
        Column(horizontalAlignment = Alignment.End) {
            val fpsColor = if (fps >= 55f) GolfGreenLight else if (fps >= 40f) GoldAccent else Color(0xFFEF5350)
            Text("${fps.toInt()} fps", fontSize = 13.sp, color = fpsColor, fontWeight = FontWeight.Bold)
            Text("60fps lock", fontSize = 9.sp, color = Color(0xFF9E9E9E))
        }
    }
}

// ─── Bottom Controls ──────────────────────────────────────────────────────────

@Composable
fun BoxScope.BottomControls(
    selectedClub: ClubType,
    isReady: Boolean,
    shotInProgress: Boolean,
    onShoot: () -> Unit,
    onSimulate: () -> Unit,
    onClubSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.75f))
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // SHOOT button
        Button(
            onClick  = onShoot,
            enabled  = isReady && !shotInProgress,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            colors   = ButtonDefaults.buttonColors(
                containerColor         = GolfGreenLight,
                disabledContainerColor = Color(0xFF2E4A2E)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                if (shotInProgress) "⏳  Tracking..." else "⛳  SHOOT",
                fontSize = 18.sp, fontWeight = FontWeight.Black,
                color = if (isReady && !shotInProgress) Color.White else Color(0xFF9E9E9E)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Club selector
            OutlinedButton(
                onClick = onClubSelect,
                modifier = Modifier.weight(1f).height(48.dp),
                colors  = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                border  = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                shape   = RoundedCornerShape(12.dp)
            ) {
                Text("🏌️ ${selectedClub.displayName}", fontSize = 13.sp)
            }
            // Simulate
            OutlinedButton(
                onClick = onSimulate,
                modifier = Modifier.weight(1f).height(48.dp),
                colors  = ButtonDefaults.outlinedButtonColors(contentColor = GoldAccent),
                border  = BorderStroke(1.dp, GoldAccent.copy(alpha = 0.5f)),
                shape   = RoundedCornerShape(12.dp)
            ) {
                Text("🎯 Simulate", fontSize = 13.sp, color = GoldAccent)
            }
        }
    }
}

// ─── Shot Result Sheet — Full Launch Monitor Display ─────────────────────────

@Composable
fun BoxScope.ShotResultSheet(
    result: ShotResult,
    metrics: SwingMetrics,
    club: ClubType,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = false) {},
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape  = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                // Handle
                Box(
                    modifier = Modifier
                        .width(44.dp).height(4.dp)
                        .background(Color(0xFF555555), CircleShape)
                        .align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(18.dp))

                // Shot shape + club header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            result.shotShape.displayName,
                            fontSize = 26.sp, fontWeight = FontWeight.Black,
                            color = GoldAccent
                        )
                        Text(
                            "${club.displayName}  •  ${(metrics.confidence * 100).toInt()}% confidence",
                            fontSize = 12.sp, color = Color(0xFF9E9E9E)
                        )
                    }
                    Text(landingEmoji(result.landingZone), fontSize = 38.sp)
                }

                Spacer(Modifier.height(20.dp))

                // ── Distance row ────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    BigStat("${result.carryYards.toInt()}",  "yds", "Carry",  GolfGreenLight)
                    BigStat("${result.totalYards.toInt()}",  "yds", "Total",  Color.White)
                    BigStat(
                        "${abs(result.offlineFeet).toInt()}", "ft",
                        if (result.offlineFeet < 0) "Left" else "Right",
                        Color(0xFFEF5350)
                    )
                }

                Spacer(Modifier.height(18.dp))
                Divider(color = Color(0xFF1E3A1E))
                Spacer(Modifier.height(18.dp))

                // ── Ball data ────────────────────────────────────────────────
                SectionHeader("BALL DATA")
                Spacer(Modifier.height(10.dp))
                MetricsGrid(listOf(
                    Triple("Ball Speed",   "${metrics.ballSpeedMph.toInt()} mph",          Color.White),
                    Triple("Club Speed",   "${metrics.clubHeadSpeedMph.toInt()} mph",       Color.White),
                    Triple("Smash Factor", String.format("%.2f", metrics.smashFactor),      GolfGreenLight),
                    Triple("Max Height",   "${result.maxHeightFeet.toInt()} ft",            SkyBlue),
                    Triple("Carry",        "${result.carryYards.toInt()} yds",              GolfGreenLight),
                    Triple("Total",        "${result.totalYards.toInt()} yds",              Color.White),
                ))

                Spacer(Modifier.height(14.dp))
                Divider(color = Color(0xFF1E3A1E))
                Spacer(Modifier.height(14.dp))

                // ── Launch conditions ────────────────────────────────────────
                SectionHeader("LAUNCH CONDITIONS")
                Spacer(Modifier.height(10.dp))
                MetricsGrid(listOf(
                    Triple("Launch Angle (VLA)", "${String.format("%.1f", metrics.launchAngleDegrees)}°", GolfGreenLight),
                    Triple("Horiz. Launch (HLA)", "${String.format("%.1f", metrics.horizontalLaunchDeg)}°", Color.White),
                    Triple("Dynamic Loft",        "${String.format("%.1f", metrics.dynamicLoftDeg)}°",     GoldAccent),
                    Triple("Attack Angle",         "${String.format("%.1f", metrics.attackAngleDeg)}°",    Color.White),
                ))

                Spacer(Modifier.height(14.dp))
                Divider(color = Color(0xFF1E3A1E))
                Spacer(Modifier.height(14.dp))

                // ── Spin ─────────────────────────────────────────────────────
                SectionHeader("SPIN & TRAJECTORY")
                Spacer(Modifier.height(10.dp))
                MetricsGrid(listOf(
                    Triple("Total Spin",  "${metrics.spinRpm.toInt()} rpm",                     GoldAccent),
                    Triple("Backspin",    "${metrics.backspinRpm.toInt()} rpm",                  Color.White),
                    Triple("Sidespin",    "${String.format("%.0f", metrics.sidespinRpm)} rpm",   Color.White),
                    Triple("Spin Axis",   "${String.format("%.1f", metrics.spinAxisDeg)}°",      GoldAccent),
                ))

                Spacer(Modifier.height(14.dp))
                Divider(color = Color(0xFF1E3A1E))
                Spacer(Modifier.height(14.dp))

                // ── Swing parameters ─────────────────────────────────────────
                SectionHeader("SWING DATA")
                Spacer(Modifier.height(10.dp))
                MetricsGrid(listOf(
                    Triple("Swing Path",
                        "${String.format("%.1f", metrics.swingPathDegrees)}° ${if (metrics.swingPathDegrees > 0) "Out" else "In"}",
                        Color.White),
                    Triple("Face Angle",
                        "${String.format("%.1f", metrics.faceAngleDegrees)}° ${if (metrics.faceAngleDegrees > 0) "Open" else "Closed"}",
                        if (abs(metrics.faceAngleDegrees) > 3) Color(0xFFEF5350) else GolfGreenLight),
                    Triple("Face-to-Path",
                        "${String.format("%.1f", metrics.faceAngleDegrees - metrics.swingPathDegrees)}°",
                        Color.White),
                    Triple("Land Angle",  "${String.format("%.1f", result.landAngleDeg)}°", Color(0xFF9E9E9E)),
                ))

                Spacer(Modifier.height(18.dp))

                // ── Landing zone ──────────────────────────────────────────────
                val zoneColor = when (result.landingZone) {
                    LandingZone.FAIRWAY, LandingZone.GREEN -> GolfGreenLight
                    LandingZone.CUP    -> GoldAccent
                    LandingZone.ROUGH  -> Color(0xFF8BC34A)
                    LandingZone.BUNKER -> SandBunker
                    LandingZone.WATER  -> WaterHazard
                    LandingZone.OOB    -> Color(0xFFEF5350)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(zoneColor.copy(0.15f), RoundedCornerShape(12.dp))
                        .border(1.dp, zoneColor.copy(0.5f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Landed: ${result.landingZone.name.replace("_", " ")}",
                        color = zoneColor, fontWeight = FontWeight.Bold, fontSize = 16.sp
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Mini trajectory
                ShotTrajectoryView(result)

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick  = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = GolfGreenLight),
                    shape    = RoundedCornerShape(14.dp)
                ) {
                    Text("Next Shot", fontSize = 16.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

// ─── Shared sub-composables ───────────────────────────────────────────────────

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        color = Color(0xFF9E9E9E),
        letterSpacing = 2.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
fun BigStat(value: String, unit: String, label: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 34.sp, fontWeight = FontWeight.Black, color = valueColor)
            Text(unit, fontSize = 13.sp, color = Color(0xFF9E9E9E),
                modifier = Modifier.padding(bottom = 6.dp, start = 2.dp))
        }
        Text(label, fontSize = 11.sp, color = Color(0xFF9E9E9E))
    }
}

@Composable
fun MetricsGrid(items: List<Triple<String, String, Color>>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { (label, value, color) ->
                    Card(
                        modifier = Modifier.weight(1f),
                        colors   = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                        shape    = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            Text(label, fontSize = 11.sp, color = Color(0xFF9E9E9E))
                            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
                        }
                    }
                }
                // If odd number, fill last slot
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun ShotTrajectoryView(result: ShotResult) {
    if (result.flightPath.isEmpty()) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp)
            .background(DarkSurfaceVariant, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
    ) {
        val maxZ = result.flightPath.maxOfOrNull { it.z }?.coerceAtLeast(1.0) ?: 1.0
        val maxY = result.flightPath.maxOfOrNull { it.y }?.coerceAtLeast(1.0) ?: 1.0
        Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            val pts = result.flightPath
            for (i in 1 until pts.size) {
                val x0 = (pts[i - 1].z / maxZ * size.width).toFloat()
                val y0 = (size.height - pts[i - 1].y / maxY * size.height).toFloat()
                val x1 = (pts[i].z / maxZ * size.width).toFloat()
                val y1 = (size.height - pts[i].y / maxY * size.height).toFloat()
                val t  = i.toFloat() / pts.size
                drawLine(
                    color       = Color(0xFF00E676).copy(alpha = 0.4f + t * 0.5f),
                    start       = Offset(x0, y0),
                    end         = Offset(x1, y1),
                    strokeWidth = 2.dp.toPx(),
                    cap         = StrokeCap.Round
                )
            }
            // Landing dot
            val lx = (pts.last().z / maxZ * size.width).toFloat()
            val ly = size.height
            drawCircle(GoldAccent, 5.dp.toPx(), Offset(lx, ly))
        }
    }
}

@Composable
fun BoxScope.ShotInProgressOverlay() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.95f)),
            shape  = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(36.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = GolfGreenLight, modifier = Modifier.size(52.dp), strokeWidth = 4.dp)
                Spacer(Modifier.height(18.dp))
                Text("Tracking Ball...", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Measuring launch conditions", color = Color(0xFF9E9E9E), fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun NoCameraPermissionOverlay() {
    Box(modifier = Modifier.fillMaxSize().background(DarkBackground), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text("📷", fontSize = 64.sp)
            Spacer(Modifier.height(16.dp))
            Text("Camera Permission Required", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                "Grant camera access in Settings to enable ball tracking.",
                fontSize = 14.sp, color = Color(0xFF9E9E9E), textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

private fun landingEmoji(zone: LandingZone) = when (zone) {
    LandingZone.FAIRWAY -> "🌿"
    LandingZone.GREEN   -> "🟢"
    LandingZone.CUP     -> "⛳"
    LandingZone.ROUGH   -> "🍃"
    LandingZone.BUNKER  -> "🏖️"
    LandingZone.WATER   -> "💧"
    LandingZone.OOB     -> "🚫"
}
