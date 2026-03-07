package com.golfsim.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
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

@Composable
fun SimulatorScreen(vm: GolfSimViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val selectedClub by vm.selectedClub.collectAsState()
    val trackingState by vm.cameraManager.trackingState.collectAsState()
    val isReadyToShoot by vm.isReadyToShoot.collectAsState()
    val shotInProgress by vm.shotInProgress.collectAsState()
    val showShotResult by vm.showShotResult.collectAsState()
    val lastResult by vm.lastShotResult.collectAsState()
    val lastMetrics by vm.lastSwingMetrics.collectAsState()
    val ballPos by vm.ballPositionOnScreen.collectAsState()
    val fps by vm.cameraManager.fps.collectAsState()

    val hasCameraPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // ─── Camera Preview ────────────────────────────────────────────────
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

        // ─── Tracking Overlay (ball detection dots) ────────────────────────
        val ballHistory by vm.cameraManager.detectedBallPositions.collectAsState()
        TrackingOverlay(ballHistory = ballHistory, currentBallPos = ballPos)

        // ─── Top HUD ───────────────────────────────────────────────────────
        TopHUD(
            club = selectedClub,
            fps = fps,
            trackingState = trackingState.toString().substringAfterLast("."),
            onBack = { vm.navigateTo(Screen.HOME) }
        )

        // ─── Bottom Controls ───────────────────────────────────────────────
        BottomControls(
            selectedClub = selectedClub,
            isReady = isReadyToShoot,
            shotInProgress = shotInProgress,
            onShoot = { vm.startSwingCapture() },
            onSimulate = { vm.simulateShotManually() },
            onClubSelect = { vm.navigateTo(Screen.CLUB_SELECT) },
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // ─── Shot In Progress overlay ──────────────────────────────────────
        if (shotInProgress) {
            ShotInProgressOverlay()
        }

        // ─── Shot Result Sheet ─────────────────────────────────────────────
        if (showShotResult && lastResult != null && lastMetrics != null) {
            ShotResultSheet(
                result = lastResult!!,
                metrics = lastMetrics!!,
                club = selectedClub,
                onDismiss = { vm.dismissShotResult() }
            )
        }
    }
}

@Composable
fun TrackingOverlay(ballHistory: List<com.golfsim.app.models.BallPosition>, currentBallPos: Pair<Float, Float>?) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Draw trail
        if (ballHistory.size > 1) {
            ballHistory.takeLast(20).forEachIndexed { i, pos ->
                val alpha = (i.toFloat() / 20f)
                drawCircle(
                    color = Color.Yellow.copy(alpha = alpha * 0.6f),
                    radius = 4.dp.toPx(),
                    center = Offset(pos.x, pos.y)
                )
            }
        }

        // Draw current ball position
        currentBallPos?.let { (x, y) ->
            // Outer ring
            drawCircle(
                color = Color.Green.copy(alpha = 0.5f),
                radius = 20.dp.toPx(),
                center = Offset(x, y),
                style = Stroke(width = 2.dp.toPx())
            )
            // Inner dot
            drawCircle(
                color = Color.White,
                radius = 6.dp.toPx(),
                center = Offset(x, y)
            )
        }
    }
}

@Composable
fun TopHUD(club: ClubType, fps: Float, trackingState: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back button
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
        ) {
            Icon(Icons.Default.ArrowBack, null, tint = Color.White)
        }

        // Club + FPS info
        Row(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("CLUB", fontSize = 9.sp, color = Color(0xFFA0A0A0), letterSpacing = 1.sp)
                Text(club.displayName, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Divider(modifier = Modifier.width(1.dp).height(30.dp), color = Color.Gray)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("FPS", fontSize = 9.sp, color = Color(0xFFA0A0A0), letterSpacing = 1.sp)
                Text("${fps.toInt()}", fontSize = 14.sp,
                    color = if (fps > 30) GolfGreenLight else Color.Red,
                    fontWeight = FontWeight.Bold)
            }
        }

        // Tracking status
        Box(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            val statusColor = when {
                trackingState.contains("Ball") -> GolfGreenLight
                trackingState.contains("Swing") -> GoldAccent
                else -> Color(0xFFA0A0A0)
            }
            val statusText = when {
                trackingState.contains("BallDetected") -> "Ball ✓"
                trackingState.contains("SwingInProgress") -> "Swing!"
                trackingState.contains("Analyzing") -> "Analysis..."
                else -> "Scanning..."
            }
            Text(statusText, fontSize = 12.sp, color = statusColor, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun BottomControls(
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
    ) {
        // Setup tip
        AnimatedVisibility(!isReady && !shotInProgress) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Place golf ball in camera view to begin\nMake sure ball is on tee or ground, well lit",
                    color = Color(0xFFCCCCCC),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(colors = listOf(Color.Transparent, Color.Black.copy(0.95f)))
                )
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Club selector
            OutlinedButton(
                onClick = onClubSelect,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                border = BorderStroke(1.dp, Color.Gray),
                modifier = Modifier.width(110.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(selectedClub.icon, fontSize = 20.sp)
                    Text(selectedClub.displayName, fontSize = 11.sp, color = Color.White)
                }
            }

            // Main shoot button
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val pulseAnim = rememberInfiniteTransition(label = "pulse")
                val scale by pulseAnim.animateFloat(
                    initialValue = 1f, targetValue = if (isReady) 1.05f else 1f,
                    animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                    label = "scale"
                )

                Button(
                    onClick = onShoot,
                    enabled = !shotInProgress,
                    modifier = Modifier
                        .size(80.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isReady) GolfGreenLight else Color(0xFF388E3C),
                        disabledContainerColor = Color(0xFF1B5E20)
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    if (shotInProgress) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
                    } else {
                        Text(
                            if (isReady) "🏌️" else "📷",
                            fontSize = 30.sp
                        )
                    }
                }
                Text(
                    if (isReady) "SHOOT" else "READY",
                    fontSize = 11.sp,
                    color = Color.White,
                    letterSpacing = 1.sp
                )
            }

            // Simulate button
            OutlinedButton(
                onClick = onSimulate,
                enabled = !shotInProgress,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = GoldAccent),
                border = BorderStroke(1.dp, GoldAccent.copy(alpha = 0.6f)),
                modifier = Modifier.width(110.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🎯", fontSize = 20.sp)
                    Text("Simulate", fontSize = 11.sp, color = GoldAccent)
                }
            }
        }
    }
}

@Composable
fun BoxScope.BottomControls(
    selectedClub: ClubType,
    isReady: Boolean,
    shotInProgress: Boolean,
    onShoot: () -> Unit,
    onSimulate: () -> Unit,
    onClubSelect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
    ) {
        AnimatedVisibility(!isReady && !shotInProgress) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "📸 Place golf ball in camera view\nEnsure good lighting for best tracking",
                    color = Color(0xFFCCCCCC),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(colors = listOf(Color.Transparent, Color.Black.copy(0.95f)))
                )
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(
                onClick = onClubSelect,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                border = BorderStroke(1.dp, Color.Gray),
                modifier = Modifier.width(110.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(selectedClub.icon, fontSize = 20.sp)
                    Text(selectedClub.displayName, fontSize = 11.sp, color = Color.White)
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = onShoot,
                    enabled = !shotInProgress,
                    modifier = Modifier.size(80.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isReady) GolfGreenLight else Color(0xFF388E3C),
                        disabledContainerColor = Color(0xFF1B5E20)
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    if (shotInProgress) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
                    } else {
                        Text(if (isReady) "🏌️" else "📷", fontSize = 30.sp)
                    }
                }
                Text(if (isReady) "SHOOT" else "READY", fontSize = 11.sp, color = Color.White, letterSpacing = 1.sp)
            }

            OutlinedButton(
                onClick = onSimulate,
                enabled = !shotInProgress,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = GoldAccent),
                border = BorderStroke(1.dp, GoldAccent.copy(alpha = 0.6f)),
                modifier = Modifier.width(110.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🎯", fontSize = 20.sp)
                    Text("Simulate", fontSize = 11.sp, color = GoldAccent)
                }
            }
        }
    }
}

@Composable
fun BoxScope.ShotInProgressOverlay() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.95f)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = GolfGreenLight, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text("Analyzing shot...", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Processing ball flight data", color = Color(0xFF9E9E9E), fontSize = 14.sp)
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
                "Please grant camera permission to use the ball tracking system.",
                color = Color(0xFF9E9E9E), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

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
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = false) {},
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Handle bar
                Box(
                    modifier = Modifier
                        .width(40.dp).height(4.dp)
                        .background(Color.Gray, CircleShape)
                        .align(Alignment.CenterHorizontally)
                )

                Spacer(Modifier.height(16.dp))

                // Shot shape header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(result.shotShape.displayName, fontSize = 24.sp, fontWeight = FontWeight.Black, color = GoldAccent)
                        Text("${club.displayName} • ${metrics.confidence.times(100).toInt()}% confidence",
                            fontSize = 13.sp, color = Color(0xFF9E9E9E))
                    }
                    Text(
                        landingEmoji(result.landingZone),
                        fontSize = 40.sp
                    )
                }

                Spacer(Modifier.height(20.dp))

                // Main distance numbers
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    BigStat("${result.carryYards.toInt()}", "yds", "Carry", GolfGreenLight)
                    BigStat("${result.totalYards.toInt()}", "yds", "Total", Color.White)
                    BigStat("${abs(result.offlineFeet).toInt()}", "ft", "${if (result.offlineFeet < 0) "Left" else "Right"}", Color(0xFFEF5350))
                }

                Spacer(Modifier.height(16.dp))
                Divider(color = Color(0xFF2E4A2E))
                Spacer(Modifier.height(16.dp))

                // Launch metrics
                Text("LAUNCH CONDITIONS", fontSize = 11.sp, color = Color(0xFF9E9E9E), letterSpacing = 1.5.sp)
                Spacer(Modifier.height(12.dp))

                MetricsGrid(
                    listOf(
                        Triple("Ball Speed", "${metrics.ballSpeedMph.toInt()} mph", Color.White),
                        Triple("Club Speed", "${metrics.clubHeadSpeedMph.toInt()} mph", Color.White),
                        Triple("Launch Angle", "${String.format("%.1f", metrics.launchAngleDegrees)}°", GolfGreenLight),
                        Triple("Spin Rate", "${metrics.spinRpm.toInt()} rpm", GoldAccent),
                        Triple("Smash Factor", String.format("%.2f", metrics.smashFactor), Color.White),
                        Triple("Max Height", "${result.maxHeightFeet.toInt()} ft", SkyBlue),
                        Triple("Swing Path", "${String.format("%.1f", metrics.swingPathDegrees)}°", Color.White),
                        Triple("Face Angle", "${String.format("%.1f", metrics.faceAngleDegrees)}°",
                            if (abs(metrics.faceAngleDegrees) > 3) Color(0xFFEF5350) else GolfGreenLight),
                    )
                )

                Spacer(Modifier.height(16.dp))

                // Landing zone badge
                val zoneColor = when (result.landingZone) {
                    LandingZone.FAIRWAY, LandingZone.GREEN -> GolfGreenLight
                    LandingZone.CUP -> GoldAccent
                    LandingZone.ROUGH -> Color(0xFF8BC34A)
                    LandingZone.BUNKER -> SandBunker
                    LandingZone.WATER -> WaterHazard
                    LandingZone.OOB -> Color(0xFFEF5350)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(zoneColor.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                        .border(1.dp, zoneColor.copy(0.5f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Landed: ${result.landingZone.name.replace("_", " ")}",
                        color = zoneColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Mini flight path visualization
                ShotTrajectoryView(result)

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GolfGreenLight),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Next Shot", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
fun BigStat(value: String, unit: String, label: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 36.sp, fontWeight = FontWeight.Black, color = valueColor)
            Text(unit, fontSize = 14.sp, color = Color(0xFF9E9E9E), modifier = Modifier.padding(bottom = 6.dp, start = 2.dp))
        }
        Text(label, fontSize = 12.sp, color = Color(0xFF9E9E9E))
    }
}

@Composable
fun MetricsGrid(items: List<Triple<String, String, Color>>) {
    items.chunked(2).forEach { row ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (label, value, color) ->
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, fontSize = 12.sp, color = Color(0xFF9E9E9E))
                        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
fun ShotTrajectoryView(result: ShotResult) {
    if (result.flightPath.isEmpty()) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .background(DarkSurfaceVariant, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
    ) {
        val maxZ = result.flightPath.maxOfOrNull { it.z }?.coerceAtLeast(1.0) ?: 1.0
        val maxY = result.flightPath.maxOfOrNull { it.y }?.coerceAtLeast(1.0) ?: 1.0

        Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            val w = size.width
            val h = size.height

            val pts = result.flightPath.filter { it.z >= 0 && it.y >= 0 }
            if (pts.size < 2) return@Canvas

            val path = androidx.compose.ui.graphics.Path()
            pts.forEachIndexed { i, pt ->
                val x = (pt.z / maxZ * w).toFloat()
                val y = (h - (pt.y / maxY * h)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            // Draw ground
            drawLine(Color(0xFF4CAF50), Offset(0f, h), Offset(w, h), strokeWidth = 2f)

            // Draw trajectory
            drawPath(path, Brush.horizontalGradient(listOf(GolfGreenLight, GoldAccent)), style = Stroke(3f))

            // Landing dot
            val last = pts.last()
            drawCircle(Color(0xFFEF5350), 6f, Offset((last.z / maxZ * w).toFloat(), h))
        }

        // Labels
        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Tee", fontSize = 9.sp, color = Color(0xFF9E9E9E))
            Text("${result.carryYards.toInt()} yds carry", fontSize = 9.sp, color = GolfGreenLight)
        }
    }
}

private fun landingEmoji(zone: LandingZone): String = when (zone) {
    LandingZone.FAIRWAY -> "🌿"
    LandingZone.GREEN -> "🟢"
    LandingZone.CUP -> "🏆"
    LandingZone.ROUGH -> "🌱"
    LandingZone.BUNKER -> "🏖️"
    LandingZone.WATER -> "💧"
    LandingZone.OOB -> "🚫"
}
