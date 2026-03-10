package com.golfsim.app.camera

/**
 * All user-tunable parameters for ball detection.
 * Persisted to DataStore and applied live to GolfCameraManager.
 */
data class CalibrationProfile(
    // Brightness
    val brightnessThreshold: Int = 200,     // 0-255 Y-plane luminance floor

    // Size
    val minBallRadius: Int = 4,             // pixels
    val maxBallRadius: Int = 120,           // pixels

    // Shape
    val circularityThreshold: Float = 0.55f, // 0.0-1.0

    // Physical setup
    val cameraDistanceFeet: Int = 10,       // feet from ball
    val cameraHeightInches: Int = 18,       // inches off ground

    // Motion
    val swingMotionThresholdPx: Float = 12f, // px/frame to trigger swing
    val swingConfirmFrames: Int = 3,         // consecutive frames needed

    // Manual hint (normalised 0-1 coords where user tapped)
    val manualHintX: Float = 0.5f,
    val manualHintY: Float = 0.5f,
    val useManualHint: Boolean = false
)
