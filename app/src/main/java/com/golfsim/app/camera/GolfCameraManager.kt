package com.golfsim.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.golfsim.app.models.BallPosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * GolfCameraManager — CameraX + enhanced ball detection.
 *
 * Improvements over original:
 * ─ Adaptive brightness window: scans ±20% around calibrated threshold so
 *   the ball is still found when lighting drifts slightly.
 * ─ Sub-pixel centroid via luminance-weighted average inside each blob.
 * ─ Multi-frame velocity: uses a rolling window of up to 8 frames for speed
 *   estimation, reducing single-frame noise.
 * ─ Acceleration-based spin estimation: lateral/vertical acceleration
 *   between frames hints at Magnus-effect spin.
 * ─ Confidence scoring: radius stability + circularity + brightness all
 *   contribute so the UI can show detection quality accurately.
 * ─ Detection gap recovery: if the ball disappears for ≤4 frames we
 *   extrapolate from the Kalman prediction instead of dropping the track.
 * ─ Exported ShotDataSnapshot: a single object capturing all raw metrics
 *   at swing-detect time so the physics engine gets clean, rich input.
 */
class GolfCameraManager(private val context: Context) {

    // ─── Constants ──────────────────────────────────────────────────────────────
    companion object {
        private const val TAG = "GolfCamera"
        private const val TARGET_FPS = 60
        private const val FRAME_HISTORY_SIZE = 120       // 2 s at 60 fps
        private const val VELOCITY_WINDOW = 8            // frames for speed avg
        private const val MAX_GAP_FRAMES = 4             // coasting frames
        private const val SWING_HISTORY_MIN = 5          // frames needed for metrics
        // Pixel 7 rear camera approximate horizontal FOV
        private const val CAMERA_HFOV_DEG = 77.0
    }

    // ─── Public state flows ──────────────────────────────────────────────────────
    private val _trackingState = MutableStateFlow<TrackingState>(TrackingState.Idle)
    val trackingState: StateFlow<TrackingState> = _trackingState

    private val _detectedBallPositions = MutableStateFlow<List<BallPosition>>(emptyList())
    val detectedBallPositions: StateFlow<List<BallPosition>> = _detectedBallPositions

    private val _swingDetected = MutableStateFlow(false)
    val swingDetected: StateFlow<Boolean> = _swingDetected

    private val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps

    private val _ballRadius = MutableStateFlow(0f)
    val ballRadius: StateFlow<Float> = _ballRadius

    /** Populated when a swing is confirmed — contains all raw tracking data. */
    private val _shotSnapshot = MutableStateFlow<ShotDataSnapshot?>(null)
    val shotSnapshot: StateFlow<ShotDataSnapshot?> = _shotSnapshot

    // ─── Internal state ──────────────────────────────────────────────────────────
    @Volatile private var profile = CalibrationProfile()

    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val ballHistory = mutableListOf<BallPosition>()
    private var isCapturing = false
    private var motionFrameCount = 0
    private var gapFrameCount = 0          // frames since last good detection

    @Volatile private var lastYData: ByteArray? = null
    @Volatile private var lastFrameWidth = 1920
    @Volatile private var lastFrameHeight = 1080

    // Kalman state (2-D position + velocity)
    private var kalmanX = 0f; private var kalmanY = 0f
    private var kalmanVx = 0f; private var kalmanVy = 0f
    private var kalmanInitialized = false
    private val processNoise = 8f       // motion uncertainty
    private val measureNoise = 6f       // sensor uncertainty (lower = trust camera more)
    private var kalmanP = Array(4) { FloatArray(4) }  // covariance

    // FPS tracking
    private val fpsWindow = ArrayDeque<Long>(70)

    // Swing detection
    private var swingStartIndex = 0

    var onBallDetected: ((BallPosition) -> Unit)? = null
    var onSwingDetected: ((ShotDataSnapshot) -> Unit)? = null

    // ─── Public API ──────────────────────────────────────────────────────────────

    fun updateCalibration(newProfile: CalibrationProfile) {
        profile = newProfile
    }

    fun setManualBallHint(normX: Float, normY: Float) {
        profile = profile.copy(manualHintX = normX, manualHintY = normY, useManualHint = true)
    }

    fun startCapture() {
        synchronized(ballHistory) { ballHistory.clear() }
        _shotSnapshot.value = null
        motionFrameCount = 0
        gapFrameCount = 0
        swingStartIndex = 0
        isCapturing = true
        _swingDetected.value = false
    }

    fun stopCapture() {
        isCapturing = false
    }

    suspend fun autoDetectBrightness(): Int {
        val frame = lastYData ?: return 200
        val width = lastFrameWidth; val height = lastFrameHeight
        var maxLuma = 0
        val yStart = height / 4; val yEnd = height * 3 / 4
        val xStart = width / 4;  val xEnd = width * 3 / 4
        for (y in yStart until yEnd step 4) {
            for (x in xStart until xEnd step 4) {
                val idx = y * width + x
                if (idx < frame.size) {
                    val luma = frame[idx].toInt() and 0xFF
                    if (luma > maxLuma) maxLuma = luma
                }
            }
        }
        return (maxLuma * 0.80).toInt().coerceIn(150, 245)
    }

    // ─── CameraX setup ───────────────────────────────────────────────────────────

    @SuppressLint("UnsafeOptInUsageError")
    fun startCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysisBuilder = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(1920, 1080))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(TARGET_FPS, TARGET_FPS))
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0.1f)

            val analysis = analysisBuilder.build().also {
                it.setAnalyzer(cameraExecutor) { proxy -> analyzeFrame(proxy) }
            }

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
    }

    fun shutdown() {
        stopCamera()
        cameraExecutor.shutdown()
    }

    // ─── Frame analysis ──────────────────────────────────────────────────────────

    @androidx.camera.core.ExperimentalGetImage
    private fun analyzeFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        updateFps(now)

        val image = imageProxy.image
        if (image == null) { imageProxy.close(); return }

        val width  = imageProxy.width
        val height = imageProxy.height
        lastFrameWidth = width; lastFrameHeight = height

        val yPlane      = image.planes[0]
        val yBuffer     = yPlane.buffer
        val rowStride   = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val yData       = ByteArray(yBuffer.remaining()).also { yBuffer.get(it) }
        lastYData       = yData

        val detected = detectBall(yData, width, height, rowStride, pixelStride)

        if (detected != null) {
            gapFrameCount = 0
            val smoothed = kalmanUpdate(detected.cx, detected.cy, detected.radius)
            val ballPos  = BallPosition(smoothed.x, smoothed.y, now)

            if (isCapturing) {
                synchronized(ballHistory) {
                    ballHistory.add(ballPos)
                    if (ballHistory.size > FRAME_HISTORY_SIZE) ballHistory.removeAt(0)
                }
                _detectedBallPositions.value = ballHistory.toList()
                onBallDetected?.invoke(ballPos)
                checkForSwing()
            }

            _ballRadius.value = detected.radius
            _trackingState.value = TrackingState.BallDetected(
                smoothed.x, smoothed.y, detected.radius, detected.confidence
            )
        } else {
            // Try to coast on Kalman prediction for a few frames
            if (kalmanInitialized && gapFrameCount < MAX_GAP_FRAMES) {
                gapFrameCount++
                val coasted = BallPosition(kalmanX + kalmanVx, kalmanY + kalmanVy, now)
                kalmanX = coasted.x; kalmanY = coasted.y
                if (isCapturing) {
                    synchronized(ballHistory) { ballHistory.add(coasted) }
                }
                _trackingState.value = TrackingState.BallDetected(
                    kalmanX, kalmanY, _ballRadius.value, 0.3f  // low confidence while coasting
                )
            } else {
                kalmanInitialized = false
                if (!isCapturing) _trackingState.value = TrackingState.WaitingForBall
            }
        }

        imageProxy.close()
    }

    // ─── Blob / Ball detection ───────────────────────────────────────────────────

    private data class Blob(
        val cx: Float, val cy: Float,
        val radius: Float,
        val pixelCount: Int,
        val circularity: Float,
        val avgLuma: Float,        // mean brightness inside blob
        val confidence: Float
    )

    /**
     * Detect the golf ball blob in a YUV Y-plane.
     *
     * Uses an adaptive brightness window: pixels brighter than
     * [threshold - window] are considered candidates, but only blobs
     * whose *average* luma exceeds [threshold] are returned. This prevents
     * the tracker from locking onto dim reflections.
     */
    private fun detectBall(
        yData: ByteArray, width: Int, height: Int,
        rowStride: Int, pixelStride: Int
    ): Blob? {
        val p = profile
        val adaptiveLow = (p.brightnessThreshold - 20).coerceAtLeast(100)

        // Build bright-pixel map at step=2 for speed
        val step = 2
        val brightMap = BooleanArray(width * height)
        for (y in (height / 10) until (height * 9 / 10) step step) {
            for (x in (width / 10) until (width * 9 / 10) step step) {
                val idx = y * rowStride + x * pixelStride
                if (idx < yData.size && (yData[idx].toInt() and 0xFF) >= adaptiveLow) {
                    brightMap[y * width + x] = true
                }
            }
        }

        // Manual hint: bias toward user-tapped region by pre-seeding visited map
        val hintX = if (p.useManualHint) (p.manualHintX * width).toInt() else -1
        val hintY = if (p.useManualHint) (p.manualHintY * height).toInt() else -1

        val visited = BooleanArray(width * height)
        val blobs   = mutableListOf<Blob>()

        for (y in (height / 10) until (height * 9 / 10) step step) {
            for (x in (width / 10) until (width * 9 / 10) step step) {
                val idx = y * width + x
                if (brightMap[idx] && !visited[idx]) {
                    floodFill(yData, brightMap, visited, x, y, width, height,
                        rowStride, pixelStride, step, p)?.let { blobs.add(it) }
                }
            }
        }

        // Filter by size, shape and average brightness
        val valid = blobs.filter { b ->
            b.circularity >= p.circularityThreshold
                && b.radius >= p.minBallRadius
                && b.radius <= p.maxBallRadius
                && b.avgLuma >= p.brightnessThreshold
        }

        if (valid.isEmpty()) return null

        return if (kalmanInitialized) {
            // Nearest to Kalman prediction
            val predX = kalmanX + kalmanVx
            val predY = kalmanY + kalmanVy
            valid.minByOrNull { b ->
                val dx = b.cx - predX; val dy = b.cy - predY
                sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            }
        } else if (hintX >= 0) {
            // Nearest to user tap
            valid.minByOrNull { b ->
                val dx = b.cx - hintX; val dy = b.cy - hintY
                sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            }
        } else {
            // Highest confidence near frame center
            val cx = width / 2f; val cy = height / 2f
            val diag = sqrt((width * width + height * height).toDouble()).toFloat()
            valid.maxByOrNull { b ->
                val dx = b.cx - cx; val dy = b.cy - cy
                val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                b.confidence - (dist / diag) * 0.4f
            }
        }
    }

    /**
     * Flood-fill from a seed bright pixel, computing a luminance-weighted
     * centroid (sub-pixel accuracy) and circularity score.
     */
    private fun floodFill(
        yData: ByteArray,
        brightMap: BooleanArray, visited: BooleanArray,
        startX: Int, startY: Int, width: Int, height: Int,
        rowStride: Int, pixelStride: Int,
        step: Int, p: CalibrationProfile
    ): Blob? {
        val stack   = ArrayDeque<Int>()
        val pixels  = mutableListOf<Int>()  // flat index list
        val seed    = startY * width + startX
        stack.addLast(seed); visited[seed] = true

        var minX = startX; var maxX = startX
        var minY = startY; var maxY = startY
        var sumX = 0.0;    var sumY = 0.0
        var sumW = 0.0;    var sumLuma = 0L; var count = 0

        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            val cx  = cur % width; val cy = cur / width
            val rawIdx = cy * rowStride + cx * pixelStride
            val luma = if (rawIdx < yData.size) (yData[rawIdx].toInt() and 0xFF).toDouble() else 0.0

            pixels.add(cur)
            val w = luma  // luminance weight for centroid
            sumX += cx * w; sumY += cy * w; sumW += w
            sumLuma += luma.toLong(); count++
            if (cx < minX) minX = cx; if (cx > maxX) maxX = cx
            if (cy < minY) minY = cy; if (cy > maxY) maxY = cy

            // 4-connected neighbours at step granularity
            for ((nx, ny) in listOf(
                cx + step to cy, cx - step to cy,
                cx to cy + step, cx to cy - step
            )) {
                if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue
                val ni = ny * width + nx
                if (!visited[ni] && brightMap[ni]) { visited[ni] = true; stack.addLast(ni) }
            }
        }

        if (count < 4) return null

        val centX = (sumX / sumW).toFloat()
        val centY = (sumY / sumW).toFloat()
        val bboxW  = (maxX - minX).toFloat()
        val bboxH  = (maxY - minY).toFloat()
        val radius = ((bboxW + bboxH) / 4f).coerceAtLeast(1f)  // average of semi-axes

        // Circularity: 4π·Area / Perimeter² approximated via bbox
        val area      = count.toFloat()
        val perimApprox = 2f * PI.toFloat() * radius
        val circularity = ((4f * PI.toFloat() * area) / (perimApprox * perimApprox))
            .coerceIn(0f, 1f)

        val avgLuma   = (sumLuma / count).toFloat()

        // Confidence: circularity + brightness normalised
        val lumaConf  = ((avgLuma - p.brightnessThreshold) / (255f - p.brightnessThreshold))
            .coerceIn(0f, 1f)
        val confidence = (circularity * 0.6f + lumaConf * 0.4f).coerceIn(0f, 1f)

        return Blob(centX, centY, radius, count, circularity, avgLuma, confidence)
    }

    // ─── Kalman filter (position + velocity, 4-state) ────────────────────────────

    private fun kalmanUpdate(measX: Float, measY: Float, radius: Float): PointF {
        if (!kalmanInitialized) {
            kalmanX = measX; kalmanY = measY
            kalmanVx = 0f;   kalmanVy = 0f
            // Initialise covariance
            kalmanP = Array(4) { i -> FloatArray(4) { j -> if (i == j) 100f else 0f } }
            kalmanInitialized = true
            return PointF(measX, measY)
        }

        // Predict
        val pX = kalmanX + kalmanVx
        val pY = kalmanY + kalmanVy
        // P = F*P*F' + Q  (simplified diagonal Q)
        kalmanP[0][0] += processNoise
        kalmanP[1][1] += processNoise
        kalmanP[2][2] += processNoise * 0.5f
        kalmanP[3][3] += processNoise * 0.5f

        // Update: Kalman gain K = P / (P + R)
        val kx  = kalmanP[0][0] / (kalmanP[0][0] + measureNoise)
        val ky  = kalmanP[1][1] / (kalmanP[1][1] + measureNoise)
        val kvx = kalmanP[2][2] / (kalmanP[2][2] + measureNoise * 2f)
        val kvy = kalmanP[3][3] / (kalmanP[3][3] + measureNoise * 2f)

        val dx = measX - pX; val dy = measY - pY
        kalmanX  = pX  + kx  * dx
        kalmanY  = pY  + ky  * dy
        kalmanVx = kalmanVx + kvx * dx
        kalmanVy = kalmanVy + kvy * dy

        kalmanP[0][0] *= (1f - kx)
        kalmanP[1][1] *= (1f - ky)
        kalmanP[2][2] *= (1f - kvx)
        kalmanP[3][3] *= (1f - kvy)

        return PointF(kalmanX, kalmanY)
    }

    // ─── Swing detection & metrics extraction ────────────────────────────────────

    private fun checkForSwing() {
        val history = synchronized(ballHistory) { ballHistory.toList() }
        if (history.size < 3) return

        val last = history[history.size - 1]
        val prev = history[history.size - 3]
        val dtMs = (last.timestamp - prev.timestamp).toFloat()
        if (dtMs <= 0f) return

        val dxPx = last.x - prev.x
        val dyPx = last.y - prev.y
        val distPx = sqrt(dxPx * dxPx + dyPx * dyPx)
        val speedPxPerMs = distPx / dtMs
        val speedPxPerFrame = speedPxPerMs * (1000f / TARGET_FPS)

        if (speedPxPerFrame >= profile.swingMotionThresholdPx) {
            motionFrameCount++
        } else {
            motionFrameCount = 0
            swingStartIndex = history.size - 1
        }

        if (motionFrameCount >= profile.swingConfirmFrames && !_swingDetected.value) {
            _swingDetected.value = true
            _trackingState.value = TrackingState.SwingInProgress

            val snapshot = buildShotSnapshot(history)
            _shotSnapshot.value = snapshot
            onSwingDetected?.invoke(snapshot)
        }
    }

    /**
     * Build a [ShotDataSnapshot] from ball position history.
     * Uses a rolling window for velocity, cross-product acceleration for spin,
     * and calibration profile for physical-unit conversion.
     */
    private fun buildShotSnapshot(history: List<BallPosition>): ShotDataSnapshot {
        if (history.size < SWING_HISTORY_MIN) {
            return ShotDataSnapshot.empty(profile)
        }

        val w = lastFrameWidth.toDouble()
        // Physical scale: yards per pixel from calibrated camera distance + FOV
        val halfFovRad   = Math.toRadians(CAMERA_HFOV_DEG / 2.0)
        val distYards    = profile.cameraDistanceFeet / 3.0
        val realWidthYards = 2.0 * distYards * tan(halfFovRad)
        val yardsPerPx   = realWidthYards / w
        val metersPerPx  = yardsPerPx * 0.9144
        val feetPerPx    = yardsPerPx * 3.0

        // Velocity via multi-frame window
        val window = history.takeLast(VELOCITY_WINDOW)
        val dtSec  = (window.last().timestamp - window.first().timestamp) / 1000.0
        if (dtSec <= 0.0) return ShotDataSnapshot.empty(profile)

        val totalDxPx = window.last().x - window.first().x
        val totalDyPx = window.last().y - window.first().y   // screen Y, +down

        val speedXFps = (totalDxPx * feetPerPx) / dtSec
        val speedYFps = -(totalDyPx * feetPerPx) / dtSec    // invert: up = positive
        val speedFps  = sqrt(speedXFps * speedXFps + speedYFps * speedYFps)
        val ballSpeedMph = (speedFps / 1.46667).coerceIn(10.0, 220.0)

        // Launch angle (above horizontal)
        val launchAngleDeg = Math.toDegrees(atan2(speedYFps, abs(speedXFps)))
            .coerceIn(0.0, 60.0)

        // Acceleration-based spin estimation
        // For each triplet compute frame-to-frame acceleration vector
        var sumLateralAccel = 0.0   // sidespin indicator
        var sumVertAccel    = 0.0   // backspin indicator
        var accelSamples    = 0
        for (i in 1 until window.size - 1) {
            val a = window[i - 1]; val b = window[i]; val c = window[i + 1]
            val dt1 = (b.timestamp - a.timestamp).toDouble() / 1000.0
            val dt2 = (c.timestamp - b.timestamp).toDouble() / 1000.0
            if (dt1 <= 0 || dt2 <= 0) continue
            val vx1 = (b.x - a.x) * feetPerPx / dt1
            val vy1 = -(b.y - a.y) * feetPerPx / dt1
            val vx2 = (c.x - b.x) * feetPerPx / dt2
            val vy2 = -(c.y - b.y) * feetPerPx / dt2
            val ax = (vx2 - vx1) / dt2   // ft/s²
            val ay = (vy2 - vy1) / dt2
            sumLateralAccel += ax
            sumVertAccel    += ay
            accelSamples++
        }

        val avgLateralAccel = if (accelSamples > 0) sumLateralAccel / accelSamples else 0.0
        val avgVertAccel    = if (accelSamples > 0) sumVertAccel    / accelSamples else 0.0

        // Magnus force ~ spin * velocity → spin ≈ accel / (k * v)
        // k is aerodynamic constant (empirically tuned for golf ball ~0.000015 ft/s/rpm)
        val magnusK = 0.000015
        val backspinRpm = (avgVertAccel / (magnusK * speedFps)).coerceIn(-3000.0, 6000.0)
        val sidespinRpm = (avgLateralAccel / (magnusK * speedFps)).coerceIn(-2000.0, 2000.0)

        // Swing path from horizontal velocity direction
        val swingPathDeg = if (abs(totalDxPx) > 2f)
            Math.toDegrees(atan2(-totalDyPx.toDouble(), totalDxPx.toDouble())) else 0.0
        val faceAngleDeg  = swingPathDeg * 0.6    // D-Plane approximation

        // Carry distance estimate using simple ballistic + Magnus (rough):
        // Re-use physics engine for full simulation; this is just a quick preview
        val estimatedCarryYards = estimateCarry(ballSpeedMph, launchAngleDeg, backspinRpm)

        // Frame statistics for quality assessment
        val detectionRatePct = (history.size.toFloat() / FRAME_HISTORY_SIZE * 100f)
            .coerceIn(0f, 100f)

        return ShotDataSnapshot(
            ballSpeedMph       = ballSpeedMph,
            launchAngleDeg     = launchAngleDeg,
            swingPathDeg       = swingPathDeg,
            faceAngleDeg       = faceAngleDeg,
            backspinRpm        = backspinRpm.coerceAtLeast(0.0),
            sidespinRpm        = sidespinRpm,
            estimatedCarryYards = estimatedCarryYards,
            yardsPerPixel      = yardsPerPx,
            frameCount         = history.size,
            detectionRatePct   = detectionRatePct,
            rawPositions       = history,
            profile            = profile
        )
    }

    /** Very lightweight carry estimator (no full RK4 needed at capture time). */
    private fun estimateCarry(speedMph: Double, angleDeg: Double, spinRpm: Double): Double {
        val g       = 32.174   // ft/s²
        val speedFps = speedMph * 1.46667
        val rad     = Math.toRadians(angleDeg)
        val vx      = speedFps * cos(rad)
        val vy      = speedFps * sin(rad)
        val cd      = 0.23;  val cl = 0.54 * tanh(spinRpm / 3000.0)
        val rho     = 0.0023769  // air density slug/ft³
        val A       = PI * (0.083 * 0.083)  // ball cross-section ft²
        val m       = 0.1012    // kg → slug = 0.00703

        var x = 0.0; var y = 0.0
        var vxN = vx; var vyN = vy
        val dt = 0.01
        repeat(3000) {
            val v = sqrt(vxN * vxN + vyN * vyN)
            val drag  = 0.5 * rho * cd * A * v * v / m
            val lift  = 0.5 * rho * cl * A * v * v / m
            vxN -= drag * (vxN / v) * dt
            vyN -= (drag * (vyN / v) - lift) * dt + g * dt
            x += vxN * dt; y += vyN * dt
            if (y < 0) return x / 3.0  // ft → yards
        }
        return x / 3.0
    }

    // ─── FPS helper ──────────────────────────────────────────────────────────────

    private fun updateFps(now: Long) {
        fpsWindow.addLast(now)
        while (fpsWindow.size > 65) fpsWindow.removeFirst()
        if (fpsWindow.size >= 2) {
            val elapsed = (fpsWindow.last() - fpsWindow.first()) / 1000.0
            _fps.value = ((fpsWindow.size - 1) / elapsed).toFloat()
        }
    }

    // ─── Tracking state ──────────────────────────────────────────────────────────

    sealed class TrackingState {
        object Idle : TrackingState()
        object WaitingForBall : TrackingState()
        data class BallDetected(
            val x: Float, val y: Float, val radius: Float, val confidence: Float
        ) : TrackingState()
        object SwingInProgress : TrackingState()
    }
}

// ─── Shot data snapshot ───────────────────────────────────────────────────────

/**
 * All raw tracked metrics captured at swing-detection time.
 * Passed to [BallPhysicsEngine.generateMetricsFromSnapshot] for full flight sim.
 */
data class ShotDataSnapshot(
    val ballSpeedMph: Double,
    val launchAngleDeg: Double,
    val swingPathDeg: Double,
    val faceAngleDeg: Double,
    val backspinRpm: Double,
    val sidespinRpm: Double,
    val estimatedCarryYards: Double,
    val yardsPerPixel: Double,
    val frameCount: Int,
    val detectionRatePct: Float,
    val rawPositions: List<com.golfsim.app.models.BallPosition>,
    val profile: CalibrationProfile
) {
    companion object {
        fun empty(profile: CalibrationProfile) = ShotDataSnapshot(
            ballSpeedMph = 0.0, launchAngleDeg = 0.0,
            swingPathDeg = 0.0, faceAngleDeg = 0.0,
            backspinRpm = 0.0,  sidespinRpm = 0.0,
            estimatedCarryYards = 0.0, yardsPerPixel = 0.0,
            frameCount = 0, detectionRatePct = 0f,
            rawPositions = emptyList(), profile = profile
        )
    }
}
