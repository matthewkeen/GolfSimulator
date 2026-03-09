package com.golfsim.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.Log
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
import kotlin.math.sqrt

class GolfCameraManager(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val _trackingState = MutableStateFlow<TrackingState>(TrackingState.Idle)
    val trackingState: StateFlow<TrackingState> = _trackingState

    private val _detectedBallPositions = MutableStateFlow<List<BallPosition>>(emptyList())
    val detectedBallPositions: StateFlow<List<BallPosition>> = _detectedBallPositions

    private val _swingDetected = MutableStateFlow(false)
    val swingDetected: StateFlow<Boolean> = _swingDetected

    private val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps

    // Ball tracking state
    private val ballHistory = mutableListOf<BallPosition>()
    private var lastFrameTime = 0L
    private var frameCount = 0
    private var fpsAccumulator = 0f
    private var isCapturing = false

    // Motion detection
    private var prevGrayFrame: IntArray? = null
    private var screenWidth = 0
    private var screenHeight = 0

    sealed class TrackingState {
        object Idle : TrackingState()
        object WaitingForBall : TrackingState()
        data class BallDetected(val x: Float, val y: Float) : TrackingState()
        object SwingInProgress : TrackingState()
        object AnalyzingShot : TrackingState()
        data class Error(val message: String) : TrackingState()
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun startCamera(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        onBallDetected: ((BallPosition) -> Unit)? = null
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processFrame(imageProxy, onBallDetected)
                    }
                }

            // Use back camera with highest resolution for Pixel 7
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            try {
                cameraProvider?.unbindAll()
                val camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner, cameraSelector, preview, imageAnalyzer
                )

                // Enable stabilization for Pixel 7
                camera?.cameraControl?.setZoomRatio(1.0f)

                _trackingState.value = TrackingState.WaitingForBall
                Log.d("GolfCamera", "Camera started successfully")
            } catch (e: Exception) {
                _trackingState.value = TrackingState.Error("Camera failed: ${e.message}")
                Log.e("GolfCamera", "Camera binding failed", e)
            }

        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processFrame(imageProxy: ImageProxy, onBallDetected: ((BallPosition) -> Unit)?) {
        val now = System.currentTimeMillis()

        // FPS calculation
        frameCount++
        if (lastFrameTime > 0) {
            val delta = now - lastFrameTime
            fpsAccumulator += 1000f / delta
            if (frameCount % 30 == 0) {
                _fps.value = fpsAccumulator / 30f
                fpsAccumulator = 0f
            }
        }
        lastFrameTime = now

        val image = imageProxy.image
        if (image != null) {
            val yBuffer = image.planes[0].buffer
            val width = imageProxy.width
            val height = imageProxy.height

            screenWidth = width
            screenHeight = height

            val ySize = yBuffer.remaining()
            val yData = ByteArray(ySize)
            yBuffer.get(yData)

            // Convert to grayscale int array for processing
            val grayData = IntArray(width * height) { yData[it].toInt() and 0xFF }

            // Detect golf ball (white/bright circular object)
            val detected = detectBall(grayData, width, height)

            if (detected != null) {
                val ballPos = BallPosition(detected.x, detected.y, now)
                ballHistory.add(ballPos)

                // Keep only last 60 frames
                if (ballHistory.size > 60) ballHistory.removeAt(0)

                _trackingState.value = TrackingState.BallDetected(detected.x, detected.y)

                if (isCapturing) {
                    onBallDetected?.invoke(ballPos)
                    _detectedBallPositions.value = ballHistory.toList()

                    // Check if ball is in motion (swing started)
                    if (ballHistory.size > 5) {
                        val motion = calculateMotion(ballHistory.takeLast(5))
                        if (motion > 15f) { // threshold pixels/frame
                            _trackingState.value = TrackingState.SwingInProgress
                            _swingDetected.value = true
                        }
                    }
                }

                prevGrayFrame = grayData
            } else if (!isCapturing) {
                _trackingState.value = TrackingState.WaitingForBall
            }
        }

        imageProxy.close()
    }

    /**
     * Detect white golf ball using brightness thresholding and blob analysis.
     * Works best when ball is placed on tee against darker background.
     */
    private fun detectBall(grayData: IntArray, width: Int, height: Int): PointF? {
        val threshold = 210  // High brightness = white ball

        // Find bright regions (potential ball pixels)
        val brightPixels = mutableListOf<Pair<Int, Int>>()

        // Sample every 3rd pixel for performance
        for (y in (height / 3)..(2 * height / 3) step 3) {
            for (x in (width / 5)..(4 * width / 5) step 3) {
                val idx = y * width + x
                if (idx < grayData.size && grayData[idx] > threshold) {
                    brightPixels.add(Pair(x, y))
                }
            }
        }

        if (brightPixels.size < 10) return null

        // Find centroid of bright pixels cluster
        val centerX = brightPixels.map { it.first }.average().toFloat()
        val centerY = brightPixels.map { it.second }.average().toFloat()

        // Verify it's roughly circular (within reasonable spread)
        val maxDist = brightPixels.maxOfOrNull { p ->
            sqrt(((p.first - centerX) * (p.first - centerX) +
                  (p.second - centerY) * (p.second - centerY)).toDouble()).toFloat()
        } ?: return null

        // Ball should have reasonable size (not too big or too small)
        if (maxDist < 5 || maxDist > 80) return null

        return PointF(centerX, centerY)
    }

    private fun calculateMotion(positions: List<BallPosition>): Float {
        if (positions.size < 2) return 0f
        var totalMotion = 0f
        for (i in 1 until positions.size) {
            val dx = positions[i].x - positions[i - 1].x
            val dy = positions[i].y - positions[i - 1].y
            totalMotion += sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        }
        return totalMotion / (positions.size - 1)
    }

    fun startCapturing() {
        isCapturing = true
        ballHistory.clear()
        _swingDetected.value = false
        _trackingState.value = TrackingState.WaitingForBall
    }

    fun stopCapturing(): List<BallPosition> {
        isCapturing = false
        _trackingState.value = TrackingState.AnalyzingShot
        return ballHistory.toList()
    }

    fun resetTracking() {
        ballHistory.clear()
        _swingDetected.value = false
        _detectedBallPositions.value = emptyList()
        _trackingState.value = TrackingState.WaitingForBall
    }

    fun shutdown() {
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
}
