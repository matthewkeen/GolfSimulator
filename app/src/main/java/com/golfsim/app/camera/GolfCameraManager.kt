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

class GolfCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "GolfCamera"
        private const val TARGET_FPS = 60
        private const val FRAME_HISTORY_SIZE = 90
    }

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

    @Volatile private var profile = CalibrationProfile()

    fun updateCalibration(newProfile: CalibrationProfile) {
        profile = newProfile
    }

    fun setManualBallHint(normX: Float, normY: Float) {
        profile = profile.copy(manualHintX = normX, manualHintY = normY, useManualHint = true)
    }

    suspend fun autoDetectBrightness(): Int {
        val frame = lastYData ?: return 200
        val width = lastFrameWidth
        val height = lastFrameHeight
        var maxLuma = 0
        val yStart = height / 4; val yEnd = height * 3 / 4
        val xStart = width / 4; val xEnd = width * 3 / 4
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

    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ballHistory = mutableListOf<BallPosition>()
    private var isCapturing = false
    private var motionFrameCount = 0
    @Volatile private var lastYData: ByteArray? = null
    @Volatile private var lastFrameWidth = 0
    @Volatile private var lastFrameHeight = 0
    private val frameTimestamps = ArrayDeque<Long>(70)
    private var kalmanX = 0f; private var kalmanY = 0f
    private var kalmanVx = 0f; private var kalmanVy = 0f
    private var kalmanInitialized = false
    private val kalmanQ = 0.01f; private val kalmanR = 5.0f

    sealed class TrackingState {
        object Idle : TrackingState()
        object WaitingForBall : TrackingState()
        data class BallDetected(val x: Float, val y: Float, val radius: Float, val confidence: Float) : TrackingState()
        object SwingInProgress : TrackingState()
        object AnalyzingShot : TrackingState()
        data class Error(val message: String) : TrackingState()
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun startCamera(previewView: PreviewView, lifecycleOwner: LifecycleOwner, onBallDetected: ((BallPosition) -> Unit)? = null) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()
            val previewBuilder = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_16_9)
            Camera2Interop.Extender(previewBuilder).apply {
                setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(TARGET_FPS, TARGET_FPS))
                setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0.35f)
                setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, 4_000_000L)
            }
            val preview = previewBuilder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysisBuilder = ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(TARGET_FPS, TARGET_FPS))
            val analyzer = analysisBuilder.build().also { it.setAnalyzer(cameraExecutor) { img -> processFrame(img, onBallDetected) } }
            val selector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()
            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(lifecycleOwner, selector, preview, analyzer)
                _trackingState.value = TrackingState.WaitingForBall
            } catch (e: Exception) {
                _trackingState.value = TrackingState.Error("Camera failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processFrame(imageProxy: ImageProxy, onBallDetected: ((BallPosition) -> Unit)?) {
        val now = System.currentTimeMillis()
        updateFps(now)
        val image = imageProxy.image
        if (image == null) { imageProxy.close(); return }
        val width = imageProxy.width; val height = imageProxy.height
        lastFrameWidth = width; lastFrameHeight = height
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val rowStride = yPlane.rowStride; val pixelStride = yPlane.pixelStride
        val yData = ByteArray(yBuffer.remaining()); yBuffer.get(yData)
        lastYData = yData
        val detected = detectBall(yData, width, height, rowStride, pixelStride)
        if (detected != null) {
            val smoothed = kalmanUpdate(detected.cx, detected.cy)
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
            _trackingState.value = TrackingState.BallDetected(smoothed.x, smoothed.y, detected.radius, detected.confidence)
        } else {
            kalmanInitialized = false
            if (!isCapturing) _trackingState.value = TrackingState.WaitingForBall
        }
        imageProxy.close()
    }

    private data class Blob(val cx: Float, val cy: Float, val radius: Float, val pixelCount: Int, val circularity: Float, val confidence: Float)

    private fun detectBall(yData: ByteArray, width: Int, height: Int, rowStride: Int, pixelStride: Int): Blob? {
        val p = profile
        val brightMap = BooleanArray(width * height)
        val step = 2
        for (y in (height/10) until (height*9/10) step step) {
            for (x in (width/10) until (width*9/10) step step) {
                val idx = y * rowStride + x * pixelStride
                if (idx < yData.size && (yData[idx].toInt() and 0xFF) >= p.brightnessThreshold) {
                    brightMap[y * width + x] = true
                }
            }
        }
        val visited = BooleanArray(width * height)
        val blobs = mutableListOf<Blob>()
        for (y in (height/10) until (height*9/10) step step) {
            for (x in (width/10) until (width*9/10) step step) {
                val idx = y * width + x
                if (brightMap[idx] && !visited[idx]) {
                    floodFill(brightMap, visited, x, y, width, height, step, p)?.let { blobs.add(it) }
                }
            }
        }
        val valid = blobs.filter { it.circularity >= p.circularityThreshold && it.radius >= p.minBallRadius && it.radius <= p.maxBallRadius }
        if (valid.isEmpty()) return null
        return if (kalmanInitialized) {
            val predX = kalmanX + kalmanVx; val predY = kalmanY + kalmanVy
            valid.minByOrNull { b -> val dx=b.cx-predX; val dy=b.cy-predY; sqrt((dx*dx+dy*dy).toDouble()).toFloat() }
        } else {
            val cx = width/2f; val cy = height/2f
            valid.maxByOrNull { b ->
                val dx=b.cx-cx; val dy=b.cy-cy
                val dist=sqrt((dx*dx+dy*dy).toDouble()).toFloat()
                val diag=sqrt((width*width+height*height).toDouble()).toFloat()
                b.circularity - (dist/diag)*0.5f
            }
        }
    }

    private fun floodFill(brightMap: BooleanArray, visited: BooleanArray, startX: Int, startY: Int, width: Int, height: Int, step: Int, p: CalibrationProfile): Blob? {
        val stack = ArrayDeque<Int>(); val pixels = mutableListOf<Pair<Int,Int>>()
        val startIdx = startY * width + startX; stack.addLast(startIdx); visited[startIdx] = true
        var iters = 0; val maxIter = p.maxBallRadius * p.maxBallRadius * 4
        while (stack.isNotEmpty() && iters < maxIter) {
            val idx = stack.removeLast(); val px = idx % width; val py = idx / width
            pixels.add(Pair(px, py)); iters++
            arrayOf(Pair(px+step,py),Pair(px-step,py),Pair(px,py+step),Pair(px,py-step)).forEach { (nx,ny) ->
                if (nx in 0 until width && ny in 0 until height) {
                    val nIdx = ny*width+nx
                    if (brightMap[nIdx] && !visited[nIdx]) { visited[nIdx]=true; stack.addLast(nIdx) }
                }
            }
        }
        if (pixels.size < 4) return null
        val cx = pixels.map{it.first}.average().toFloat(); val cy = pixels.map{it.second}.average().toFloat()
        val minX=pixels.minOf{it.first}.toFloat(); val maxX=pixels.maxOf{it.first}.toFloat()
        val minY=pixels.minOf{it.second}.toFloat(); val maxY=pixels.maxOf{it.second}.toFloat()
        val radius=(maxX-minX+maxY-minY)/4f
        val area=pixels.size.toFloat()*step*step; val expected=PI.toFloat()*radius*radius
        val circ=(area/expected).coerceIn(0f,1f)
        val sizeScore=1f-(abs(radius-20f)/20f).coerceIn(0f,1f)
        val conf=(circ*0.7f+sizeScore*0.3f).coerceIn(0f,1f)
        return Blob(cx,cy,radius,pixels.size,circ,conf)
    }

    private fun kalmanUpdate(measX: Float, measY: Float): PointF {
        if (!kalmanInitialized) { kalmanX=measX; kalmanY=measY; kalmanVx=0f; kalmanVy=0f; kalmanInitialized=true; return PointF(measX,measY) }
        val predX=kalmanX+kalmanVx; val predY=kalmanY+kalmanVy
        val gain=kalmanQ/(kalmanQ+kalmanR)
        val newX=predX+gain*(measX-predX); val newY=predY+gain*(measY-predY)
        kalmanVx=(newX-kalmanX)*0.8f; kalmanVy=(newY-kalmanY)*0.8f
        kalmanX=newX; kalmanY=newY
        return PointF(newX,newY)
    }

    private fun checkForSwing() {
        val history = synchronized(ballHistory) { ballHistory.toList() }
        if (history.size < 4) return
        val recent = history.takeLast(4); var total=0f
        for (i in 1 until recent.size) { val dx=recent[i].x-recent[i-1].x; val dy=recent[i].y-recent[i-1].y; total+=sqrt((dx*dx+dy*dy).toDouble()).toFloat() }
        val avg=total/(recent.size-1)
        if (avg > profile.swingMotionThresholdPx) { motionFrameCount++; if (motionFrameCount>=profile.swingConfirmFrames && !_swingDetected.value) { _swingDetected.value=true; _trackingState.value=TrackingState.SwingInProgress } }
        else motionFrameCount=0
    }

    private fun updateFps(now: Long) {
        frameTimestamps.addLast(now)
        while (frameTimestamps.isNotEmpty() && now-frameTimestamps.first()>1000L) frameTimestamps.removeFirst()
        _fps.value=frameTimestamps.size.toFloat()
    }

    fun startCapturing() { synchronized(ballHistory){ballHistory.clear()}; motionFrameCount=0; kalmanInitialized=false; _swingDetected.value=false; isCapturing=true; _trackingState.value=TrackingState.WaitingForBall }
    fun stopCapturing(): List<BallPosition> { isCapturing=false; _trackingState.value=TrackingState.AnalyzingShot; return synchronized(ballHistory){ballHistory.toList()} }
    fun resetTracking() { synchronized(ballHistory){ballHistory.clear()}; motionFrameCount=0; kalmanInitialized=false; _swingDetected.value=false; _detectedBallPositions.value=emptyList(); _trackingState.value=TrackingState.WaitingForBall }
    fun shutdown() { cameraExecutor.shutdown(); cameraProvider?.unbindAll() }
}
