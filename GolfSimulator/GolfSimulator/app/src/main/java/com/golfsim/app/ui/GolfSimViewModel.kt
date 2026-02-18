package com.golfsim.app.ui

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.golfsim.app.camera.GolfCameraManager
import com.golfsim.app.game.BallPhysicsEngine
import com.golfsim.app.models.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore("golf_sim_prefs")

class GolfSimViewModel(application: Application) : AndroidViewModel(application) {

    val cameraManager = GolfCameraManager(application)
    private val gson = Gson()
    private val dataStore = application.dataStore

    // Keys
    private val ROUNDS_KEY = stringPreferencesKey("saved_rounds")
    private val SETTINGS_KEY = stringPreferencesKey("settings")

    // ─── App State ─────────────────────────────────────────────────────────────
    private val _currentScreen = MutableStateFlow(Screen.HOME)
    val currentScreen: StateFlow<Screen> = _currentScreen

    private val _selectedClub = MutableStateFlow(ClubType.DRIVER)
    val selectedClub: StateFlow<ClubType> = _selectedClub

    private val _selectedCourse = MutableStateFlow(CourseDatabase.DRIVING_RANGE)
    val selectedCourse: StateFlow<Course> = _selectedCourse

    private val _currentHole = MutableStateFlow(0)
    val currentHole: StateFlow<Int> = _currentHole

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings

    // ─── Shot & Round State ────────────────────────────────────────────────────
    private val _lastShotResult = MutableStateFlow<ShotResult?>(null)
    val lastShotResult: StateFlow<ShotResult?> = _lastShotResult

    private val _lastSwingMetrics = MutableStateFlow<SwingMetrics?>(null)
    val lastSwingMetrics: StateFlow<SwingMetrics?> = _lastSwingMetrics

    private val _currentRound = MutableStateFlow<RoundScore?>(null)
    val currentRound: StateFlow<RoundScore?> = _currentRound

    private val _savedRounds = MutableStateFlow<List<RoundScore>>(emptyList())
    val savedRounds: StateFlow<List<RoundScore>> = _savedRounds

    // ─── Camera/Tracking State ─────────────────────────────────────────────────
    private val _isReadyToShoot = MutableStateFlow(false)
    val isReadyToShoot: StateFlow<Boolean> = _isReadyToShoot

    private val _shotInProgress = MutableStateFlow(false)
    val shotInProgress: StateFlow<Boolean> = _shotInProgress

    private val _showShotResult = MutableStateFlow(false)
    val showShotResult: StateFlow<Boolean> = _showShotResult

    private val _ballPositionOnScreen = MutableStateFlow<Pair<Float, Float>?>(null)
    val ballPositionOnScreen: StateFlow<Pair<Float, Float>?> = _ballPositionOnScreen

    // ─── Statistics ────────────────────────────────────────────────────────────
    private val _sessionShots = MutableStateFlow<List<ShotResult>>(emptyList())
    val sessionShots: StateFlow<List<ShotResult>> = _sessionShots

    init {
        loadSavedRounds()
        observeCamera()
    }

    private fun observeCamera() {
        viewModelScope.launch {
            cameraManager.swingDetected.collect { detected ->
                if (detected && _shotInProgress.value) {
                    // Give brief moment for ball to travel then analyze
                    delay(500)
                    processSwing()
                }
            }
        }

        viewModelScope.launch {
            cameraManager.trackingState.collect { state ->
                when (state) {
                    is GolfCameraManager.TrackingState.BallDetected -> {
                        _ballPositionOnScreen.value = Pair(state.x, state.y)
                        _isReadyToShoot.value = true
                    }
                    is GolfCameraManager.TrackingState.WaitingForBall -> {
                        _isReadyToShoot.value = false
                    }
                    else -> {}
                }
            }
        }
    }

    private fun processSwing() {
        viewModelScope.launch {
            val captured = cameraManager.stopCapturing()
            _shotInProgress.value = false

            val metrics = if (captured.size >= 3) {
                BallPhysicsEngine.generateMetricsFromTracking(
                    ballPositions = captured,
                    club = _selectedClub.value,
                    screenWidthPx = 1080,
                    screenHeightPx = 2400
                )
            } else {
                // Simulate shot if tracking was insufficient
                BallPhysicsEngine.generateDefaultMetrics(_selectedClub.value)
            }

            val result = BallPhysicsEngine.simulate(metrics, _selectedClub.value)

            _lastSwingMetrics.value = metrics
            _lastShotResult.value = result
            _showShotResult.value = true

            // Add to session shots
            _sessionShots.value = _sessionShots.value + result
        }
    }

    fun startSwingCapture() {
        _shotInProgress.value = true
        _showShotResult.value = false
        _lastShotResult.value = null
        cameraManager.startCapturing()
    }

    fun dismissShotResult() {
        _showShotResult.value = false
        cameraManager.resetTracking()
    }

    fun simulateShotManually() {
        viewModelScope.launch {
            _shotInProgress.value = true
            _showShotResult.value = false

            delay(300) // brief animation

            val metrics = BallPhysicsEngine.generateDefaultMetrics(_selectedClub.value)
            val result = BallPhysicsEngine.simulate(metrics, _selectedClub.value)

            _lastSwingMetrics.value = metrics
            _lastShotResult.value = result
            _shotInProgress.value = false
            _showShotResult.value = true

            _sessionShots.value = _sessionShots.value + result
        }
    }

    fun selectClub(club: ClubType) {
        _selectedClub.value = club
    }

    fun selectCourse(course: Course) {
        _selectedCourse.value = course
    }

    fun startRound(playerName: String, teeBox: TeeBox) {
        _currentRound.value = RoundScore(
            courseId = _selectedCourse.value.name,
            playerName = playerName,
            teeBox = teeBox
        )
        _currentHole.value = 0
        navigateTo(Screen.SIMULATOR)
    }

    fun recordHoleScore(strokes: Int, putts: Int, fairwayHit: Boolean, girHit: Boolean) {
        val round = _currentRound.value ?: return
        val course = _selectedCourse.value
        val holeIdx = _currentHole.value

        if (holeIdx >= course.holes.size) return

        val hole = course.holes[holeIdx]
        val score = HoleScore(
            holeNumber = hole.number,
            par = hole.par,
            strokes = strokes,
            putts = putts,
            fairwayHit = fairwayHit,
            greenInRegulation = girHit,
            shots = _sessionShots.value.takeLast(strokes)
        )

        round.holeScores.add(score)

        if (holeIdx + 1 >= course.holes.size) {
            // Round complete
            saveRound(round)
            navigateTo(Screen.SCORECARD)
        } else {
            _currentHole.value = holeIdx + 1
            _sessionShots.value = emptyList()
        }
    }

    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }

    private fun saveRound(round: RoundScore) {
        viewModelScope.launch {
            val rounds = _savedRounds.value.toMutableList()
            rounds.add(0, round)
            if (rounds.size > 50) rounds.removeAt(rounds.size - 1) // keep last 50

            _savedRounds.value = rounds

            dataStore.edit { prefs ->
                prefs[ROUNDS_KEY] = gson.toJson(rounds)
            }
        }
    }

    private fun loadSavedRounds() {
        viewModelScope.launch {
            dataStore.data.collect { prefs ->
                val json = prefs[ROUNDS_KEY] ?: ""
                if (json.isNotEmpty()) {
                    try {
                        val type = object : TypeToken<List<RoundScore>>() {}.type
                        _savedRounds.value = gson.fromJson(json, type)
                    } catch (e: Exception) {
                        _savedRounds.value = emptyList()
                    }
                }
            }
        }
    }

    fun updateSettings(newSettings: AppSettings) {
        _settings.value = newSettings
    }

    fun getSessionStats(): SessionStats {
        val shots = _sessionShots.value
        if (shots.isEmpty()) return SessionStats()

        return SessionStats(
            totalShots = shots.size,
            avgCarry = shots.map { it.carryYards }.average(),
            avgTotal = shots.map { it.totalYards }.average(),
            longestDrive = shots.maxOfOrNull { it.carryYards } ?: 0.0,
            avgOffline = shots.map { Math.abs(it.offlineFeet) }.average(),
            fairwayPct = shots.count { Math.abs(it.offlineFeet) < 30 } * 100.0 / shots.size,
            shotShapeBreakdown = shots.groupBy { it.shotShape }.mapValues { it.value.size }
        )
    }

    override fun onCleared() {
        super.onCleared()
        cameraManager.shutdown()
    }
}

enum class Screen {
    HOME, SIMULATOR, SCORECARD, STATS, SETTINGS, COURSE_SELECT, CLUB_SELECT, ROUND_SETUP
}

data class AppSettings(
    val playerName: String = "Golfer",
    val defaultTeeBox: TeeBox = TeeBox.WHITE,
    val useMetric: Boolean = false,
    val showTrajectory: Boolean = true,
    val hapticFeedback: Boolean = true,
    val soundEnabled: Boolean = true,
    val cameraSetupMode: CameraSetupMode = CameraSetupMode.SIDE_VIEW,
    val sensitivity: Float = 1.0f
)

enum class CameraSetupMode(val description: String) {
    SIDE_VIEW("Camera to the side, ball on tee"),
    BEHIND_VIEW("Camera behind golfer"),
    FRONT_VIEW("Camera facing golfer")
}

data class SessionStats(
    val totalShots: Int = 0,
    val avgCarry: Double = 0.0,
    val avgTotal: Double = 0.0,
    val longestDrive: Double = 0.0,
    val avgOffline: Double = 0.0,
    val fairwayPct: Double = 0.0,
    val shotShapeBreakdown: Map<ShotShape, Int> = emptyMap()
)
