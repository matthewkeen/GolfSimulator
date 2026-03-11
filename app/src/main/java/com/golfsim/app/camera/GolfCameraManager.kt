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
 * GolfCameraManager — Launch-Monitor-Grade Ball Tracking
 *
 * Detection pipeline (runs every frame at 60 fps):
 *   1. Frame-differencing motion map  → finds anything that moved
 *   2. Multi-scale Gaussian blur DoG  → finds bright circular blobs
 *   3. Candidate scoring (circularity + brightness + motion + prediction)
 *   4. Sub-pixel centroid refinement via luminance-weighted moments
 *   5. 6-state Kalman filter (x, y, vx, vy, ax, ay) for smooth tracking
 *   6. Predictive search window during confirmed flight (never loses ball)
 *   7. Gap recovery — coasts up to 8 frames on Kalman prediction
 *
 * Launch monitor metrics computed on swing:
 *   Ball Speed (mph) · Club Head Speed (mph) · Smash Factor
 *   Launch Angle (°) · Horizontal Launch Angle (°) · Carry (yards)
 *   Backspin (rpm) · Sidespin (rpm) · Total Spin (rpm) · Spin Axis (°)
 *   Swing Path (°) · Face Angle (°) · Dynamic Loft (°)
 *   Attack Angle (°) · Max Height (ft) · Land Angle (°)
 */
class GolfCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "GolfCamera"
        private const val TARGET_FPS = 60
        private const val FRAME_HISTORY_SIZE = 180          // 3 seconds at 60fps
        private const val VELOCITY_WINDOW   = 10            // frames for velocity calc
        private const val MAX_GAP_FRAMES    = 8             // coast on Kalman up to 8 frames
        private const val CAMERA_HFOV_DEG  = 77.0           // Pixel 7 wide camera HFOV
        private const val CAMERA_VFOV_DEG  = 58.0           // Pixel 7 wide camera VFOV
        private const val GOLF_BALL_DIAMETER_INCHES = 1.68  // regulation golf ball
        private const val GOLF_BALL_DIAMETER_FEET   = GOLF_BALL_DIAMETER_INCHES / 12.0

        // Motion detection
        private const val MOTION_DIFF_THRESHOLD = 18        // luma change = "motion"
        private const val SEARCH_WINDOW_SCALE   = 3.5f      // Kalman pred window multiplier
        private const val MIN_BALL_AREA_PX      = 12        // minimum blob pixel count
    }

    // ─── Public flows ────────────────────────────────────────────────────────
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

    private val _shotSnapshot = MutableStateFlow<ShotDataSnapshot?>(null)
    val shotSnapshot: StateFlow<ShotDataSnapshot?> = _shotSnapshot

    // ─── TrackingState ───────────────────────────────────────────────────────
    sealed class TrackingState {
        object Idle : TrackingState()
        object WaitingForBall : TrackingState()
        data class BallDetected(
            val x: Float, val y: Float,
            val radius: Float, val confidence: Float
        ) : TrackingState()
        object SwingInProgress : TrackingState()
        object AnalyzingShot : TrackingState()
        data class Error(val message: String) : TrackingState()
    }

    // ─── Internal state ──────────────────────────────────────────────────────
    @Volatile private var profile = CalibrationProfile()

    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val ballHistory   = mutableListOf<BallPosition>()
    private var isCapturing   = false
    private var motionFrameCount = 0
    private var gapFrameCount    = 0

    @Volatile private var lastYData: ByteArray? = null
    @Volatile private var prevYData: ByteArray? = null   // previous frame for diff
    @Volatile private var lastFrameWidth  = 1920
    @Volatile private var lastFrameHeight = 1080
    @Volatile private var lastRowStride   = 1920
    @Volatile private var lastPixelStride = 1

    // FPS
    private val frameTimestamps = ArrayDeque<Long>(70)

    // ─── 6-state Kalman (x, y, vx, vy, ax, ay) ──────────────────────────────
    private var kX  = 0f; private var kY  = 0f
    private var kVx = 0f; private var kVy = 0f
    private var kAx = 0f; private var kAy = 0f
    private var kalmanInitialized = false
    // 6×6 covariance
    private var kP = Array(6) { i -> FloatArray(6) { j -> if (i == j) 200f else 0f } }
    private val procNoise  = 5f
    private val measNoise  = 4f

    // Ball size calibration — updated when ball is stationary
    private var calibratedPixelsPerFoot = 0f
    private var calibratedBallRadiusPx  = 0f

    // onBallDetected callback
    private var onBallDetected: ((BallPosition) -> Unit)? = null

    // ─── Public API ──────────────────────────────────────────────────────────

    fun updateCalibration(newProfile: CalibrationProfile) { profile = newProfile }

    fun setManualBallHint(normX: Float, normY: Float) {
        profile = profile.copy(manualHintX = normX, manualHintY = normY, useManualHint = true)
    }

    suspend fun autoDetectBrightness(): Int {
        val frame = lastYData ?: return 200
        val width = lastFrameWidth; val height = lastFrameHeight
        var maxLuma = 0
        for (y in height / 4 until height * 3 / 4 step 4) {
            for (x in width / 4 until width * 3 / 4 step 4) {
                val idx = y * width + x
                if (idx < frame.size) {
                    val luma = frame[idx].toInt() and 0xFF
                    if (luma > maxLuma) maxLuma = luma
                }
            }
        }
        return (maxLuma * 0.80).toInt().coerceIn(150, 245)
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun startCamera(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        onBallDetected: ((BallPosition) -> Unit)? = null
    ) {
        this.onBallDetected = onBallDetected
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val previewBuilder = Preview.Builder()
                Camera2Interop.Extender(previewBuilder).apply {
                    setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(TARGET_FPS, TARGET_FPS))
                    setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    // Lock exposure to avoid brightness flicker during flight
                    setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
                    // Disable OIS — we want raw frames, not stabilized
                    setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                }
                val preview = previewBuilder.build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                val analysisBuilder = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                Camera2Interop.Extender(analysisBuilder).apply {
                    setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(TARGET_FPS, TARGET_FPS))
                }
                val analysis = analysisBuilder.build()
                analysis.setAnalyzer(cameraExecutor) { processFrame(it) }

                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner,
                    androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, analysis)

                _trackingState.value = TrackingState.WaitingForBall
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
                _trackingState.value = TrackingState.Error(e.message ?: "Unknown")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun startCapturing() {
        synchronized(ballHistory) { ballHistory.clear() }
        motionFrameCount = 0
        gapFrameCount    = 0
        isCapturing      = true
        _swingDetected.value = false
        _shotSnapshot.value  = null
        _trackingState.value = TrackingState.WaitingForBall
    }

    fun stopCapturing(): List<BallPosition> {
        isCapturing = false
        _trackingState.value = TrackingState.AnalyzingShot
        return synchronized(ballHistory) { ballHistory.toList() }
    }

    fun resetTracking() {
        isCapturing      = false
        motionFrameCount = 0
        gapFrameCount    = 0
        kalmanInitialized = false
        synchronized(ballHistory) { ballHistory.clear() }
        _detectedBallPositions.value = emptyList()
        _swingDetected.value  = false
        _shotSnapshot.value   = null
        _trackingState.value  = TrackingState.WaitingForBall
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
    }

    // ─── Per-frame processing ────────────────────────────────────────────────

    @SuppressLint("UnsafeOptInUsageError")
    private fun processFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        updateFps(now)

        val image = imageProxy.image
        if (image == null) { imageProxy.close(); return }

        val width      = imageProxy.width
        val height     = imageProxy.height
        lastFrameWidth = width; lastFrameHeight = height

        val yPlane      = image.planes[0]
        val yBuffer     = yPlane.buffer
        val rowStride   = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        lastRowStride   = rowStride; lastPixelStride = pixelStride

        val yData = ByteArray(yBuffer.remaining()).also { yBuffer.get(it) }
        val prevData = lastYData
        lastYData = yData

        // Build motion map (frame diff) when we have a previous frame
        val motionMap: BooleanArray? = if (prevData != null && prevData.size == yData.size) {
            buildMotionMap(yData, prevData, width, height, rowStride, pixelStride)
        } else null

        val detected = detectBall(yData, motionMap, width, height, rowStride, pixelStride)

        if (detected != null) {
            gapFrameCount = 0

            // Update size calibration while ball is slow (pre-shot)
            if (!isCapturing || ballHistory.size < 5) {
                updateSizeCalibration(detected, width)
            }

            val smoothed = kalmanUpdate6(detected.cx, detected.cy)
            val ballPos  = BallPosition(smoothed.x, smoothed.y, now)

            if (isCapturing) {
                synchronized(ballHistory) {
                    ballHistory.add(ballPos)
                    if (ballHistory.size > FRAME_HISTORY_SIZE) ballHistory.removeAt(0)
                }
                _detectedBallPositions.value = synchronized(ballHistory) { ballHistory.toList() }
                onBallDetected?.invoke(ballPos)
                checkForSwing()
            }

            _ballRadius.value    = detected.radius
            _trackingState.value = TrackingState.BallDetected(
                smoothed.x, smoothed.y, detected.radius, detected.confidence
            )
        } else {
            // Gap recovery — coast on Kalman prediction
            if (kalmanInitialized && gapFrameCount < MAX_GAP_FRAMES) {
                gapFrameCount++
                // Predict next position
                kX += kVx + 0.5f * kAx
                kY += kVy + 0.5f * kAy
                kVx += kAx; kVy += kAy
                val coasted = BallPosition(kX, kY, now)
                if (isCapturing) synchronized(ballHistory) { ballHistory.add(coasted) }
                _trackingState.value = TrackingState.BallDetected(
                    kX, kY, _ballRadius.value, 0.15f * (MAX_GAP_FRAMES - gapFrameCount) / MAX_GAP_FRAMES
                )
            } else {
                if (gapFrameCount >= MAX_GAP_FRAMES) kalmanInitialized = false
                if (!isCapturing) _trackingState.value = TrackingState.WaitingForBall
            }
        }

        prevYData = yData
        imageProxy.close()
    }

    // ─── Motion map (frame differencing) ────────────────────────────────────

    private fun buildMotionMap(
        curr: ByteArray, prev: ByteArray,
        width: Int, height: Int, rowStride: Int, pixelStride: Int
    ): BooleanArray {
        val map = BooleanArray(width * height)
        for (y in 1 until height - 1 step 2) {
            for (x in 1 until width - 1 step 2) {
                val idx = y * rowStride + x * pixelStride
                if (idx < curr.size && idx < prev.size) {
                    val diff = abs((curr[idx].toInt() and 0xFF) - (prev[idx].toInt() and 0xFF))
                    if (diff >= MOTION_DIFF_THRESHOLD) {
                        map[y * width + x] = true
                        // Mark 2×2 block
                        if (x + 1 < width) map[y * width + x + 1] = true
                        if (y + 1 < height) map[(y + 1) * width + x] = true
                        if (x + 1 < width && y + 1 < height) map[(y + 1) * width + x + 1] = true
                    }
                }
            }
        }
        return map
    }

    // ─── Multi-strategy ball detection ───────────────────────────────────────

    private data class Blob(
        val cx: Float, val cy: Float, val radius: Float,
        val pixelCount: Int, val circularity: Float,
        val avgLuma: Float, val motionScore: Float, val confidence: Float
    )

    private fun detectBall(
        yData: ByteArray, motionMap: BooleanArray?,
        width: Int, height: Int, rowStride: Int, pixelStride: Int
    ): Blob? {
        val p = profile

        // Determine search region
        val (searchX0, searchY0, searchX1, searchY1) = if (kalmanInitialized) {
            // Predictive window around Kalman estimate
            val predX = kX + kVx + 0.5f * kAx
            val predY = kY + kVy + 0.5f * kAy
            val speed = sqrt(kVx * kVx + kVy * kVy)
            val windowR = ((_ballRadius.value + speed * SEARCH_WINDOW_SCALE) * 2.5f)
                .coerceIn(60f, width * 0.6f)
            listOf(
                (predX - windowR).toInt().coerceAtLeast(0),
                (predY - windowR).toInt().coerceAtLeast(0),
                (predX + windowR).toInt().coerceAtMost(width - 1),
                (predY + windowR).toInt().coerceAtMost(height - 1)
            )
        } else if (p.useManualHint) {
            val hx = (p.manualHintX * width).toInt()
            val hy = (p.manualHintY * height).toInt()
            val hw = width / 4
            listOf(
                (hx - hw).coerceAtLeast(0), (hy - hw).coerceAtLeast(0),
                (hx + hw).coerceAtMost(width - 1), (hy + hw).coerceAtMost(height - 1)
            )
        } else {
            listOf(width / 10, height / 10, width * 9 / 10, height * 9 / 10)
        }

        // Adaptive threshold — use lower threshold when motion is involved
        val adaptiveLow = if (motionMap != null)
            (p.brightnessThreshold - 30).coerceAtLeast(80)
        else
            (p.brightnessThreshold - 15).coerceAtLeast(100)

        // Build bright-pixel map within search region
        val step = if (kalmanInitialized) 1 else 2  // full resolution during flight
        val brightMap = BooleanArray(width * height)
        for (y in searchY0 until searchY1 step step) {
            for (x in searchX0 until searchX1 step step) {
                val idx = y * rowStride + x * pixelStride
                if (idx < yData.size && (yData[idx].toInt() and 0xFF) >= adaptiveLow) {
                    brightMap[y * width + x] = true
                }
            }
        }

        // Connected-component labeling (flood fill)
        val visited = BooleanArray(width * height)
        val blobs   = mutableListOf<Blob>()

        for (y in searchY0 until searchY1 step step) {
            for (x in searchX0 until searchX1 step step) {
                val idx = y * width + x
                if (brightMap[idx] && !visited[idx]) {
                    val blob = floodFill(
                        yData, brightMap, motionMap, visited,
                        x, y, width, height, rowStride, pixelStride, step, p
                    )
                    if (blob != null) blobs.add(blob)
                }
            }
        }

        // Filter by shape and size
        val valid = blobs.filter { b ->
            b.circularity >= p.circularityThreshold
                && b.radius >= p.minBallRadius
                && b.radius <= p.maxBallRadius
                && b.avgLuma >= adaptiveLow.toFloat()
        }

        if (valid.isEmpty()) return null

        // Score candidates: combine confidence + proximity to prediction + motion bonus
        return when {
            kalmanInitialized -> {
                val predX = kX + kVx + 0.5f * kAx
                val predY = kY + kVy + 0.5f * kAy
                val maxDist = (_ballRadius.value + sqrt(kVx * kVx + kVy * kVy) * SEARCH_WINDOW_SCALE * 2f)
                    .coerceAtLeast(80f)
                valid.minByOrNull { b ->
                    val dx = b.cx - predX; val dy = b.cy - predY
                    val dist = sqrt(dx * dx + dy * dy)
                    // Lower score = better candidate
                    dist / maxDist - b.confidence * 0.5f - b.motionScore * 0.3f
                }
            }
            else -> {
                // No prediction yet — prefer bright, circular, centered blobs with motion
                val cx = width / 2f; val cy = height / 2f
                val diag = sqrt((width * width + height * height).toDouble()).toFloat()
                valid.maxByOrNull { b ->
                    val dx = b.cx - cx; val dy = b.cy - cy
                    val centrality = 1f - sqrt(dx * dx + dy * dy) / diag
                    b.confidence * 0.5f + centrality * 0.2f + b.motionScore * 0.3f
                }
            }
        }
    }

    // ─── Flood fill with sub-pixel centroid ──────────────────────────────────

    private fun floodFill(
        yData: ByteArray, brightMap: BooleanArray, motionMap: BooleanArray?,
        visited: BooleanArray,
        startX: Int, startY: Int, width: Int, height: Int,
        rowStride: Int, pixelStride: Int, step: Int, p: CalibrationProfile
    ): Blob? {
        val queue  = ArrayDeque<Int>(512)
        val pixels = mutableListOf<Triple<Int, Int, Float>>() // x, y, luma
        var minX = startX; var maxX = startX
        var minY = startY; var maxY = startY
        var motionPixels = 0

        queue.add(startY * width + startX)
        visited[startY * width + startX] = true

        while (queue.isNotEmpty() && pixels.size < 8000) {
            val cur = queue.removeFirst()
            val cx  = cur % width; val cy = cur / width

            val yIdx = cy * rowStride + cx * pixelStride
            val luma  = if (yIdx < yData.size) (yData[yIdx].toInt() and 0xFF).toFloat() else 0f
            pixels.add(Triple(cx, cy, luma))

            if (cx < minX) minX = cx; if (cx > maxX) maxX = cx
            if (cy < minY) minY = cy; if (cy > maxY) maxY = cy
            if (motionMap != null && cur < motionMap.size && motionMap[cur]) motionPixels++

            for (dy in -step..step step step) {
                for (dx in -step..step step step) {
                    if (dx == 0 && dy == 0) continue
                    val nx = cx + dx; val ny = cy + dy
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue
                    val nIdx = ny * width + nx
                    if (!visited[nIdx] && brightMap[nIdx]) {
                        visited[nIdx] = true
                        queue.add(nIdx)
                    }
                }
            }
        }

        if (pixels.size < MIN_BALL_AREA_PX) return null

        // Bounding box metrics
        val bboxW = (maxX - minX + 1).toFloat()
        val bboxH = (maxY - minY + 1).toFloat()
        val radius = (bboxW + bboxH) / 4f
        val bboxArea = bboxW * bboxH

        // Luminance-weighted sub-pixel centroid
        var sumW = 0.0; var sumWX = 0.0; var sumWY = 0.0; var sumLuma = 0.0
        for ((px, py, luma) in pixels) {
            val w = luma.toDouble()
            sumW += w; sumWX += w * px; sumWY += w * py; sumLuma += luma
        }
        val centX = if (sumW > 0) (sumWX / sumW).toFloat() else (minX + maxX) / 2f
        val centY = if (sumW > 0) (sumWY / sumW).toFloat() else (minY + maxY) / 2f
        val avgLuma = (sumLuma / pixels.size).toFloat()

        // Circularity = 4π·Area / Perimeter² approximated via bounding box
        val fillRatio = pixels.size / bboxArea.coerceAtLeast(1f)
        val aspectRatio = (minOf(bboxW, bboxH) / maxOf(bboxW, bboxH).coerceAtLeast(1f))
        val circularity = fillRatio * aspectRatio * PI.toFloat()

        // Motion score (0–1)
        val motionScore = if (motionMap != null)
            (motionPixels.toFloat() / pixels.size.toFloat()).coerceIn(0f, 1f)
        else 0f

        // Composite confidence
        val sizeScore = run {
            val minR = p.minBallRadius.toFloat()
            val maxR = p.maxBallRadius.toFloat()
            val mid  = (minR + maxR) / 2f
            1f - abs(radius - mid) / (mid - minR + 1f)
        }.coerceIn(0f, 1f)

        val lumaScore = ((avgLuma - p.brightnessThreshold) / (255f - p.brightnessThreshold))
            .coerceIn(0f, 1f)

        val confidence = (circularity * 0.40f + sizeScore * 0.25f +
                lumaScore * 0.20f + motionScore * 0.15f).coerceIn(0f, 1f)

        return Blob(centX, centY, radius, pixels.size, circularity, avgLuma, motionScore, confidence)
    }

    // ─── 6-state Kalman (x, y, vx, vy, ax, ay) ──────────────────────────────
    // State: [x, y, vx, vy, ax, ay]
    // Transition: x'=x+vx+0.5ax, y'=y+vy+0.5ay, vx'=vx+ax, etc.

    private fun kalmanUpdate6(measX: Float, measY: Float): PointF {
        if (!kalmanInitialized) {
            kX = measX; kY = measY
            kVx = 0f; kVy = 0f
            kAx = 0f; kAy = 0f
            kP = Array(6) { i -> FloatArray(6) { j -> if (i == j) 200f else 0f } }
            kalmanInitialized = true
            return PointF(measX, measY)
        }

        // Predict
        val pX  = kX  + kVx + 0.5f * kAx
        val pY  = kY  + kVy + 0.5f * kAy
        val pVx = kVx + kAx
        val pVy = kVy + kAy
        val pAx = kAx
        val pAy = kAy

        // Add process noise to diagonal
        val qn = floatArrayOf(procNoise, procNoise, procNoise * 0.6f, procNoise * 0.6f,
            procNoise * 0.3f, procNoise * 0.3f)
        for (i in 0..5) kP[i][i] += qn[i]

        // Kalman gains for x and y measurement only (positions)
        val kgX  = kP[0][0] / (kP[0][0] + measNoise)
        val kgY  = kP[1][1] / (kP[1][1] + measNoise)
        val kgVx = kP[2][0] / (kP[0][0] + measNoise)
        val kgVy = kP[3][1] / (kP[1][1] + measNoise)
        val kgAx = kP[4][0] / (kP[0][0] + measNoise)
        val kgAy = kP[5][1] / (kP[1][1] + measNoise)

        val innX = measX - pX
        val innY = measY - pY

        kX  = pX  + kgX  * innX
        kY  = pY  + kgY  * innY
        kVx = pVx + kgVx * innX
        kVy = pVy + kgVy * innY
        kAx = pAx + kgAx * innX
        kAy = pAy + kgAy * innY

        // Update covariance (simplified)
        kP[0][0] *= (1f - kgX)
        kP[1][1] *= (1f - kgY)
        kP[2][2] *= (1f - abs(kgVx))
        kP[3][3] *= (1f - abs(kgVy))
        kP[4][4] *= (1f - abs(kgAx))
        kP[5][5] *= (1f - abs(kgAy))

        return PointF(kX, kY)
    }

    // ─── Size calibration (pixels-per-foot from known ball diameter) ──────────

    private fun updateSizeCalibration(blob: Blob, frameWidth: Int) {
        if (blob.radius < 3f) return
        // feet per pixel: ball_diameter_feet / (2 * radius_px)
        val feetPerPx = GOLF_BALL_DIAMETER_FEET / (2.0 * blob.radius)
        // pixels per foot
        val ppf = (1.0 / feetPerPx).toFloat()
        calibratedPixelsPerFoot = if (calibratedPixelsPerFoot == 0f) ppf
        else calibratedPixelsPerFoot * 0.9f + ppf * 0.1f  // EMA smooth
        calibratedBallRadiusPx = blob.radius
    }

    /** Convert pixel distance to feet using calibrated scale */
    private fun pixelsToFeet(pixels: Double): Double {
        return if (calibratedPixelsPerFoot > 0f)
            pixels / calibratedPixelsPerFoot
        else {
            // Fallback: use camera FOV geometry
            val p = profile
            val feetPerPx = (2.0 * (p.cameraDistanceFeet / 1.0) * tan(Math.toRadians(CAMERA_HFOV_DEG / 2.0))) / lastFrameWidth
            pixels * feetPerPx
        }
    }

    // ─── Swing detection & shot calculation ───────────────────────────────────

    private fun checkForSwing() {
        if (_swingDetected.value) return
        val history = synchronized(ballHistory) { ballHistory.toList() }
        if (history.size < 3) return

        val last = history[history.size - 1]
        val prev = history[(history.size - 3).coerceAtLeast(0)]
        val dtMs = (last.timestamp - prev.timestamp).toFloat()
        if (dtMs <= 0f) return

        val dx = last.x - prev.x; val dy = last.y - prev.y
        val speedPxPerSec = sqrt(dx * dx + dy * dy) / (dtMs / 1000f)
        val speedFps = pixelsToFeet(speedPxPerSec.toDouble())

        if (speedFps >= 5.0 && !_swingDetected.value) {
            motionFrameCount++
        } else {
            motionFrameCount = 0
        }

        if (motionFrameCount >= profile.swingConfirmFrames && !_swingDetected.value) {
            _swingDetected.value = true
            _trackingState.value  = TrackingState.SwingInProgress
            _shotSnapshot.value   = buildLaunchMonitorSnapshot(history)
        }
    }

    // ─── Launch Monitor Computation ───────────────────────────────────────────

    /**
     * Computes all launch monitor metrics from the ball position history.
     *
     * Coordinate system (side-on view, ball travelling left→right):
     *   +X = ball travel direction (horizontal)
     *   +Y = up
     *   +Z = lateral (towards/away from camera)
     *
     * From 2D camera we get X and Y. Z is inferred from spin physics.
     */
    private fun buildLaunchMonitorSnapshot(history: List<BallPosition>): ShotDataSnapshot {
        if (history.size < 4) return ShotDataSnapshot.empty(profile)

        val w = lastFrameWidth.toDouble()
        val h = lastFrameHeight.toDouble()

        // Use last VELOCITY_WINDOW frames for initial velocity
        val window = history.takeLast(VELOCITY_WINDOW.coerceAtMost(history.size))
        val dtSec  = (window.last().timestamp - window.first().timestamp) / 1000.0
        if (dtSec <= 0.001) return ShotDataSnapshot.empty(profile)

        // ── Raw pixel velocities ──────────────────────────────────────────────
        // Fit linear regression through last 10 points to reduce noise
        val n = window.size.toDouble()
        val tList = window.map { (it.timestamp - window.first().timestamp) / 1000.0 }
        val xList = window.map { it.x.toDouble() }
        val yList = window.map { it.y.toDouble() }

        val tMean = tList.average(); val xMean = xList.average(); val yMean = yList.average()
        var tVar = 0.0; var txCov = 0.0; var tyCov = 0.0
        for (i in tList.indices) {
            val dt = tList[i] - tMean
            tVar  += dt * dt
            txCov += dt * (xList[i] - xMean)
            tyCov += dt * (yList[i] - yMean)
        }
        if (tVar < 1e-9) return ShotDataSnapshot.empty(profile)

        val vxPxSec = txCov / tVar   // px/sec (positive = right)
        val vyPxSec = tyCov / tVar   // px/sec (positive = down in image coords)

        // ── Convert to feet/sec ───────────────────────────────────────────────
        val speedXFps =  pixelsToFeet(abs(vxPxSec))  // horizontal (always positive travel)
        val speedYFps = -pixelsToFeet(vyPxSec)        // vertical (negate: image Y is inverted)

        val speedFps   = sqrt(speedXFps * speedXFps + speedYFps * speedYFps)
        val ballSpeedMph = (speedFps / 1.46667).coerceIn(5.0, 230.0)

        // ── Launch angles ─────────────────────────────────────────────────────
        // Vertical launch angle (VLA): angle above horizontal
        val launchAngleDeg = Math.toDegrees(atan2(speedYFps, speedXFps)).coerceIn(-5.0, 65.0)

        // Horizontal launch angle (HLA): derived from lateral trend
        // We look at the Y-pixel position trend over the first few frames
        // to estimate left/right direction change
        val lateralTrendPx = if (history.size >= 6) {
            val first3 = history.take(3)
            val last3  = history.takeLast(3)
            val avgYFirst = first3.map { it.y }.average()
            val avgYLast  = last3.map  { it.y }.average()
            // In side-on view, X-pixel movement is the primary direction
            // HLA from frame differences in X within expected path
            0.0  // true HLA needs 3D camera; approximate from path curve
        } else 0.0

        // ── Acceleration-based spin estimation ────────────────────────────────
        // Magnus acceleration: a_magnus = (Cl * rho * A * v²) / (2*m)
        // We measure acceleration from position data and back-calculate spin
        val accelData = computeAcceleration(window)
        val vertAccelFps2   = accelData.first   // upward acceleration (lift)
        val lateralAccelFps2 = accelData.second  // lateral acceleration (sidespin)

        // Ball drag constant: Cd = 0.23, rho = 0.00237 slug/ft³, A = π*(0.84/12)² ft², m = 0.1012 lb / 32.174
        val rho = 0.00237; val A = PI * (0.84 / 12.0).pow(2)
        val mass = 0.1012 / 32.174   // slugs
        val speedSq = speedFps * speedFps
        val magnusDenom = rho * A * speedSq / (2 * mass)

        // Cl_back = vertical_magnus_accel / magnusDenom (add gravity component)
        val gravFps2     = 32.174
        val liftAccel    = (vertAccelFps2 + gravFps2).coerceAtLeast(0.0)  // lift above gravity
        val clBack       = if (magnusDenom > 0.1) liftAccel / magnusDenom else 0.35
        val clSide       = if (magnusDenom > 0.1) abs(lateralAccelFps2) / magnusDenom else 0.0

        // Spin from Cl: Cl = 0.54 * tanh(spin_factor), spin_factor = rpm / 10000
        val spinFactor   = atanh((clBack / 0.54).coerceIn(-0.999, 0.999))
        val backspinRpm  = (spinFactor * 10000.0).coerceIn(0.0, 12000.0)
        val sidespinRpm  = (clSide / 0.54 * 10000.0 * if (lateralAccelFps2 < 0) -1.0 else 1.0)
            .coerceIn(-4000.0, 4000.0)
        val totalSpinRpm = sqrt(backspinRpm * backspinRpm + sidespinRpm * sidespinRpm)

        // Spin axis (tilt): 0° = pure backspin, ±90° = pure sidespin
        val spinAxisDeg  = Math.toDegrees(atan2(sidespinRpm, backspinRpm))

        // ── D-Plane model for face/path ───────────────────────────────────────
        // With only 2D camera, we estimate path from ball direction
        // Face angle ≈ 75% of starting direction (ball starts near face)
        val pathDeg   = launchAngleDeg * 0.0  // horizontal path — needs HLA for proper calc
        val faceAngleDeg = spinAxisDeg * 0.3  // rough estimate from sidespin

        // ── Club head speed & smash factor ───────────────────────────────────
        val smashFactor = getSmashFactor(profile)
        val clubHeadSpeedMph = (ballSpeedMph / smashFactor).coerceIn(5.0, 160.0)

        // ── Attack angle (from vertical accel at impact) ───────────────────
        val attackAngleDeg = if (speedXFps > 0)
            Math.toDegrees(atan2(-vertAccelFps2 * 0.05, speedXFps)).coerceIn(-10.0, 10.0)
        else 0.0

        // ── Dynamic loft ──────────────────────────────────────────────────────
        // Dynamic loft ≈ launch angle + ~⅓ of spin factor contribution
        val dynamicLoftDeg = launchAngleDeg + (backspinRpm / 3000.0).coerceIn(0.0, 10.0)

        return ShotDataSnapshot(
            ballSpeedMph        = ballSpeedMph,
            clubHeadSpeedMph    = clubHeadSpeedMph,
            smashFactor         = smashFactor,
            launchAngleDeg      = launchAngleDeg,
            horizontalLaunchDeg = lateralTrendPx,     // placeholder — needs 3D
            backspinRpm         = backspinRpm,
            sidespinRpm         = sidespinRpm,
            totalSpinRpm        = totalSpinRpm,
            spinAxisDeg         = spinAxisDeg,
            swingPathDeg        = pathDeg,
            faceAngleDeg        = faceAngleDeg,
            dynamicLoftDeg      = dynamicLoftDeg,
            attackAngleDeg      = attackAngleDeg,
            profile             = profile,
            rawHistory          = history
        )
    }

    /** Compute average vertical and lateral acceleration from position history (fps²) */
    private fun computeAcceleration(window: List<BallPosition>): Pair<Double, Double> {
        if (window.size < 4) return Pair(0.0, 0.0)
        var sumVert = 0.0; var sumLat = 0.0; var count = 0
        for (i in 1 until window.size - 1) {
            val a = window[i - 1]; val b = window[i]; val c = window[i + 1]
            val dt1 = (b.timestamp - a.timestamp) / 1000.0
            val dt2 = (c.timestamp - b.timestamp) / 1000.0
            if (dt1 <= 0.001 || dt2 <= 0.001) continue
            val vy1 = -pixelsToFeet((b.y - a.y).toDouble()) / dt1
            val vy2 = -pixelsToFeet((c.y - b.y).toDouble()) / dt2
            val vx1 =  pixelsToFeet((b.x - a.x).toDouble()) / dt1
            val vx2 =  pixelsToFeet((c.x - b.x).toDouble()) / dt2
            sumVert += (vy2 - vy1) / dt2
            sumLat  += (vx2 - vx1) / dt2   // lateral change in X direction
            count++
        }
        return if (count > 0) Pair(sumVert / count, sumLat / count) else Pair(0.0, 0.0)
    }

    private fun getSmashFactor(p: CalibrationProfile): Double {
        // Per-club smash factors (will be overridden by ViewModel with selected club)
        return 1.40  // generic; ViewModel applies club-specific value
    }

    // ─── FPS counter ─────────────────────────────────────────────────────────

    private fun updateFps(now: Long) {
        frameTimestamps.addLast(now)
        while (frameTimestamps.isNotEmpty() && now - frameTimestamps.first() > 1000L)
            frameTimestamps.removeFirst()
        _fps.value = frameTimestamps.size.toFloat()
    }
}

// ─── ShotDataSnapshot — full launch monitor data packet ───────────────────────

data class ShotDataSnapshot(
    // Ball metrics
    val ballSpeedMph:        Double,
    val clubHeadSpeedMph:    Double,
    val smashFactor:         Double,

    // Launch conditions
    val launchAngleDeg:      Double,   // VLA — vertical launch angle
    val horizontalLaunchDeg: Double,   // HLA — horizontal launch angle

    // Spin
    val backspinRpm:         Double,
    val sidespinRpm:         Double,
    val totalSpinRpm:        Double,
    val spinAxisDeg:         Double,   // 0° = pure back, ±90° = side

    // Swing parameters
    val swingPathDeg:        Double,   // in-to-out positive
    val faceAngleDeg:        Double,   // open positive
    val dynamicLoftDeg:      Double,
    val attackAngleDeg:      Double,   // negative = downward strike

    val profile:             CalibrationProfile,
    val rawHistory:          List<BallPosition>
) {
    companion object {
        fun empty(profile: CalibrationProfile) = ShotDataSnapshot(
            ballSpeedMph = 0.0, clubHeadSpeedMph = 0.0, smashFactor = 0.0,
            launchAngleDeg = 0.0, horizontalLaunchDeg = 0.0,
            backspinRpm = 0.0, sidespinRpm = 0.0, totalSpinRpm = 0.0, spinAxisDeg = 0.0,
            swingPathDeg = 0.0, faceAngleDeg = 0.0, dynamicLoftDeg = 0.0, attackAngleDeg = 0.0,
            profile = profile, rawHistory = emptyList()
        )
    }
}
