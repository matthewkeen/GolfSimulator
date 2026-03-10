package com.golfsim.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.hardware.camera2.*
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
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

class GolfCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "GolfCamera"
        private const val TARGET_FPS = 60
        private const val FRAME_HISTORY_SIZE = 90       // 1.5 seconds at 60fps
        private const val BRIGHTNESS_THRESHOLD = 200    // White ball brightness floor
        private const val MIN_BLOB_PIXELS = 8           // Minimum pixels to count as ball
        private const val MAX_BLOB_PIXELS = 2000        // Maximum (avoids detecting sky/lights)
        private const val MIN_BALL_RADIUS_PX = 4f
        private const val MAX_BALL_RADIUS_PX = 120f
        private const val CIRCULARITY_THRESHOLD = 0.55  // 1.0 = perfect circle
        private const val MOTION_THRESHOLD_PX = 12f     // px/frame to trigger swing detection
        private const val MOTION_CONFIRM_FRAMES = 3     // consecutive frames needed to confirm swing
    }

    // ─── Public state flows ────────────────────────────────────────────────────
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

    private val _debugInfo = MutableStateFlow("")
    val debugInfo: StateFlow<String> = _debugInfo

    // ─── Internal state ────────────────────────────────────────────────────────
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val ballHistory = mutableListOf<BallPosition>()
    private var isCapturing = false
    private var motionFrameCount = 0

    // FPS tracking
    private val frameTimestamps = ArrayDeque<Long>(70)

    // Background subtraction — stores last frame's Y data for motion detection
    private var prevYData: ByteArray? = null
    private var frameWidth = 0
    private var frameHeight = 0

    // Kalman-like smoothing for ball position
    private var kalmanX = 0f
    private var kalmanY = 0f
    private var kalmanVx = 0f
    private var kalmanVy = 0f
    private var kalmanInitialized = false
    private val kalmanQ = 0.01f   // process noise
    private val kalmanR = 5.0f    // measurement noise

    sealed class TrackingState {
        object Idle : TrackingState()
        object WaitingForBall : TrackingState()
        data class BallDetected(val x: Float, val y: Float, val radius: Float, val confidence: Float) : TrackingState()
        object SwingInProgress : TrackingState()
        object AnalyzingShot : TrackingState()
        data class Error(val message: String) : TrackingState()
    }

    // ─── Camera startup ────────────────────────────────────────────────────────
    @SuppressLint("UnsafeOptInUsageError")
    fun startCamera(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        onBallDetected: ((BallPosition) -> Unit)? = null
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // ── Preview builder with 60fps hint ───────────────────────────────
            val previewBuilder = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)

            // Force 60fps on Pixel 7 via Camera2 interop
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(TARGET_FPS, TARGET_FPS)
                )
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON
                )
                .setCaptureRequestOption(
                    // Disable optical image stabilization for lower latency
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                )
                .setCaptureRequestOption(
                    // Electronic IS also off — we want raw speed
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )
                .setCaptureRequestOption(
                    // Lock focus — autofocus hunting kills frame rate
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF
                )
                .setCaptureRequestOption(
                    // Fixed focus distance — set for ~3m (golf ball distance)
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    0.35f   // diopters — 0.35 ≈ 3 metres
                )
                .setCaptureRequestOption(
                    // Reduce shutter lag — short exposure for fast ball
                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                    4_000_000L  // 4ms max exposure (avoids motion blur at 60fps)
                )

            val preview = previewBuilder.build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            // ── Image analysis builder with 60fps ─────────────────────────────
            val analysisBuilder = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(TARGET_FPS, TARGET_FPS)
                )

            imageAnalyzer = analysisBuilder.build().also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processFrame(imageProxy, onBallDetected)
                }
            }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner, cameraSelector, preview, imageAnalyzer
                )
                _trackingState.value = TrackingState.WaitingForBall
                Log.d(TAG, "Camera started at ${TARGET_FPS}fps")
            } catch (e: Exception) {
                _trackingState.value = TrackingState.Error("Camera failed: ${e.message}")
                Log.e(TAG, "Camera binding failed", e)
            }

        }, ContextCompat.getMainExecutor(context))
    }

    // ─── Per-frame processing ──────────────────────────────────────────────────
    @SuppressLint("UnsafeOptInUsageError")
    private fun processFrame(
        imageProxy: ImageProxy,
        onBallDetected: ((BallPosition) -> Unit)?
    ) {
        val now = System.currentTimeMillis()
        updateFps(now)

        val image = imageProxy.image
        if (image == null) { imageProxy.close(); return }

        val width = imageProxy.width
        val height = imageProxy.height
        frameWidth = width
        frameHeight = height

        // Extract Y (luminance) plane — fastest possible, no colour conversion needed
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride

        val ySize = yBuffer.remaining()
        val yData = ByteArray(ySize)
        yBuffer.get(yData)

        // ── Multi-stage ball detection ─────────────────────────────────────
        val detected = detectBallMultiStage(yData, width, height, rowStride, pixelStride)

        if (detected != null) {
            // Apply Kalman filter to smooth position
            val smoothed = kalmanUpdate(detected.x, detected.y)

            val ballPos = BallPosition(smoothed.x, smoothed.y, now)

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
            // Ball lost — reset Kalman
            kalmanInitialized = false
            if (!isCapturing) {
                _trackingState.value = TrackingState.WaitingForBall
            }
        }

        prevYData = yData
        imageProxy.close()
    }

    // ─── Multi-stage ball detection algorithm ──────────────────────────────────
    /**
     * Stage 1: Brightness threshold — find all pixels above threshold
     * Stage 2: Connected component labeling — group adjacent bright pixels into blobs
     * Stage 3: Circularity filter — reject non-circular blobs (lights, reflections)
     * Stage 4: Size filter — reject blobs too small or too large
     * Stage 5: Motion validation — prefer blobs that moved from last frame
     */
    private data class Blob(
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val pixelCount: Int,
        val circularity: Float,
        val confidence: Float
    )

    private fun detectBallMultiStage(
        yData: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): Blob? {

        // ── Stage 1: threshold pass — build bright pixel map ──────────────
        // Only scan the central 80% of the frame (ignore top/bottom edges)
        val yStart = height / 10
        val yEnd = height * 9 / 10
        val xStart = width / 10
        val xEnd = width * 9 / 10

        // Downsample by 2 for speed (still 30fps equivalent precision)
        val step = 2
        val brightMap = BooleanArray(width * height)

        for (y in yStart until yEnd step step) {
            for (x in xStart until xEnd step step) {
                val idx = y * rowStride + x * pixelStride
                if (idx < yData.size) {
                    val luma = yData[idx].toInt() and 0xFF
                    if (luma >= BRIGHTNESS_THRESHOLD) {
                        brightMap[y * width + x] = true
                    }
                }
            }
        }

        // ── Stage 2: simple blob detection via flood fill ─────────────────
        val visited = BooleanArray(width * height)
        val blobs = mutableListOf<Blob>()

        for (y in yStart until yEnd step step) {
            for (x in xStart until xEnd step step) {
                val idx = y * width + x
                if (brightMap[idx] && !visited[idx]) {
                    val blob = floodFill(brightMap, visited, x, y, width, height, step)
                    if (blob != null) blobs.add(blob)
                }
            }
        }

        if (blobs.isEmpty()) return null

        // ── Stage 3 & 4: filter by circularity and size ───────────────────
        val validBlobs = blobs.filter {
            it.circularity >= CIRCULARITY_THRESHOLD &&
            it.radius >= MIN_BALL_RADIUS_PX &&
            it.radius <= MAX_BALL_RADIUS_PX &&
            it.pixelCount >= MIN_BLOB_PIXELS &&
            it.pixelCount <= MAX_BLOB_PIXELS
        }

        if (validBlobs.isEmpty()) return null

        // ── Stage 5: motion validation — prefer blob closest to predicted pos ──
        val prev = prevYData
        return if (prev != null && kalmanInitialized) {
            // Prefer blob closest to where we predict ball should be
            val predictedX = kalmanX + kalmanVx
            val predictedY = kalmanY + kalmanVy
            validBlobs.minByOrNull { blob ->
                val dx = blob.cx - predictedX
                val dy = blob.cy - predictedY
                sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            }
        } else {
            // No history — pick the most circular blob near center of frame
            val cx = width / 2f
            val cy = height / 2f
            validBlobs.maxByOrNull { blob ->
                // Score = circularity - (distance from center / frame diagonal)
                val dx = blob.cx - cx
                val dy = blob.cy - cy
                val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                val diagonal = sqrt((width * width + height * height).toDouble()).toFloat()
                blob.circularity - (dist / diagonal) * 0.5f
            }
        }
    }

    /**
     * Flood fill a bright blob, computing its centroid, radius, and circularity.
     */
    private fun floodFill(
        brightMap: BooleanArray,
        visited: BooleanArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        step: Int
    ): Blob? {
        val stack = ArrayDeque<Int>()
        val pixels = mutableListOf<Pair<Int, Int>>()

        val startIdx = startY * width + startX
        stack.addLast(startIdx)
        visited[startIdx] = true

        var iterations = 0
        while (stack.isNotEmpty() && iterations < MAX_BLOB_PIXELS * 2) {
            val idx = stack.removeLast()
            val px = idx % width
            val py = idx / width
            pixels.add(Pair(px, py))
            iterations++

            // Check 4-connected neighbours (at step size)
            val neighbours = arrayOf(
                Pair(px + step, py),
                Pair(px - step, py),
                Pair(px, py + step),
                Pair(px, py - step)
            )
            for ((nx, ny) in neighbours) {
                if (nx in 0 until width && ny in 0 until height) {
                    val nIdx = ny * width + nx
                    if (brightMap[nIdx] && !visited[nIdx]) {
                        visited[nIdx] = true
                        stack.addLast(nIdx)
                    }
                }
            }
        }

        if (pixels.size < MIN_BLOB_PIXELS) return null

        // Centroid
        val cx = pixels.map { it.first }.average().toFloat()
        val cy = pixels.map { it.second }.average().toFloat()

        // Bounding box
        val minX = pixels.minOf { it.first }.toFloat()
        val maxX = pixels.maxOf { it.first }.toFloat()
        val minY = pixels.minOf { it.second }.toFloat()
        val maxY = pixels.maxOf { it.second }.toFloat()
        val bboxW = maxX - minX
        val bboxH = maxY - minY

        // Approximate radius from bounding box
        val radius = (bboxW + bboxH) / 4f

        // Circularity = 4π * area / perimeter²
        // For a blob: area ≈ pixelCount * step², perimeter ≈ 2π * radius
        val area = pixels.size.toFloat() * step * step
        val expectedArea = PI.toFloat() * radius * radius
        val circularity = (area / expectedArea).coerceIn(0f, 1f)

        // Confidence score combining circularity and size appropriateness
        val idealRadius = 20f  // typical golf ball at 10 feet
        val sizeScore = 1f - (abs(radius - idealRadius) / idealRadius).coerceIn(0f, 1f)
        val confidence = (circularity * 0.7f + sizeScore * 0.3f).coerceIn(0f, 1f)

        return Blob(cx, cy, radius, pixels.size, circularity, confidence)
    }

    // ─── Kalman filter (1D per axis, constant velocity model) ─────────────────
    private fun kalmanUpdate(measX: Float, measY: Float): PointF {
        if (!kalmanInitialized) {
            kalmanX = measX
            kalmanY = measY
            kalmanVx = 0f
            kalmanVy = 0f
            kalmanInitialized = true
            return PointF(measX, measY)
        }

        // Predict
        val predX = kalmanX + kalmanVx
        val predY = kalmanY + kalmanVy

        // Update (simplified scalar Kalman gain)
        val gain = kalmanQ / (kalmanQ + kalmanR)
        val newX = predX + gain * (measX - predX)
        val newY = predY + gain * (measY - predY)

        // Update velocity
        kalmanVx = (newX - kalmanX) * 0.8f  // 0.8 = velocity decay
        kalmanVy = (newY - kalmanY) * 0.8f

        kalmanX = newX
        kalmanY = newY

        return PointF(newX, newY)
    }

    // ─── Swing detection ───────────────────────────────────────────────────────
    private fun checkForSwing() {
        val history = synchronized(ballHistory) { ballHistory.toList() }
        if (history.size < 4) return

        // Calculate instantaneous speed over last 4 frames
        val recent = history.takeLast(4)
        var totalMotion = 0f
        for (i in 1 until recent.size) {
            val dx = recent[i].x - recent[i - 1].x
            val dy = recent[i].y - recent[i - 1].y
            totalMotion += sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        }
        val avgMotion = totalMotion / (recent.size - 1)

        if (avgMotion > MOTION_THRESHOLD_PX) {
            motionFrameCount++
            if (motionFrameCount >= MOTION_CONFIRM_FRAMES && !_swingDetected.value) {
                Log.d(TAG, "Swing detected! avg motion = $avgMotion px/frame")
                _swingDetected.value = true
                _trackingState.value = TrackingState.SwingInProgress
            }
        } else {
            motionFrameCount = 0
        }
    }

    // ─── FPS counter ───────────────────────────────────────────────────────────
    private fun updateFps(now: Long) {
        frameTimestamps.addLast(now)
        // Remove timestamps older than 1 second
        while (frameTimestamps.isNotEmpty() && now - frameTimestamps.first() > 1000L) {
            frameTimestamps.removeFirst()
        }
        _fps.value = frameTimestamps.size.toFloat()
    }

    // ─── Public controls ───────────────────────────────────────────────────────
    fun startCapturing() {
        synchronized(ballHistory) { ballHistory.clear() }
        motionFrameCount = 0
        kalmanInitialized = false
        _swingDetected.value = false
        isCapturing = true
        _trackingState.value = TrackingState.WaitingForBall
        Log.d(TAG, "Capturing started")
    }

    fun stopCapturing(): List<BallPosition> {
        isCapturing = false
        _trackingState.value = TrackingState.AnalyzingShot
        return synchronized(ballHistory) { ballHistory.toList() }
    }

    fun resetTracking() {
        synchronized(ballHistory) { ballHistory.clear() }
        motionFrameCount = 0
        kalmanInitialized = false
        _swingDetected.value = false
        _detectedBallPositions.value = emptyList()
        _trackingState.value = TrackingState.WaitingForBall
    }

    fun shutdown() {
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
}
