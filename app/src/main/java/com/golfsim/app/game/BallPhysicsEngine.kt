package com.golfsim.app.game

import com.golfsim.app.camera.ShotDataSnapshot
import com.golfsim.app.models.*
import kotlin.math.*

/**
 * Full 6DOF golf ball flight physics engine.
 * Uses Runge-Kutta 4 integration with aerodynamic models.
 */
object BallPhysicsEngine {

    // Physical constants
    private const val G           = 32.174          // ft/s²  gravity
    private const val AIR_DENSITY = 0.0765          // lb/ft³ sea level
    private const val BALL_MASS   = 0.10125         // lbs (1.62 oz)
    private const val BALL_DIAMETER = 0.141667      // ft  (1.68 in)
    private const val BALL_RADIUS   = BALL_DIAMETER / 2.0
    private const val BALL_AREA     = PI * BALL_RADIUS * BALL_RADIUS

    // Aerodynamic coefficients
    private const val CD_BASE    = 0.23   // drag (dimpled ball)
    private const val CL_MAX     = 0.54   // max lift coefficient
    private const val SPIN_DECAY = 0.98   // spin fraction remaining per second

    // ── Internal integration state ────────────────────────────────────────────
    private data class State(
        val x: Double, val y: Double, val z: Double,    // position (ft): x=lateral, y=height, z=forward
        val vx: Double, val vy: Double, val vz: Double, // velocity (ft/s)
        val spin: Double,     // backspin RPM
        val sidespin: Double  // sidespin RPM (+= slice/push for RH golfer)
    )

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC: simulate flight from SwingMetrics
    // ─────────────────────────────────────────────────────────────────────────
    fun simulate(metrics: SwingMetrics, club: ClubType): ShotResult {
        val speedFps  = metrics.ballSpeedMph * 1.46667
        val launchRad = Math.toRadians(metrics.launchAngleDegrees)
        val pathRad   = Math.toRadians(metrics.swingPathDegrees)

        val vz0 = speedFps * cos(launchRad) * cos(pathRad)
        val vx0 = speedFps * cos(launchRad) * sin(pathRad)
        val vy0 = speedFps * sin(launchRad)

        var state = State(
            x = 0.0, y = 0.0, z = 0.0,
            vx = vx0, vy = vy0, vz = vz0,
            spin = metrics.spinRpm,
            sidespin = metrics.sidespinRpm
        )

        val flightPath = mutableListOf<FlightPoint>()
        var t = 0.0
        val dt = 0.01   // 10 ms steps

        var maxY = 0.0
        var prevVy = vy0
        var landVy = 0.0
        var landVz = 0.0

        while (state.y >= 0 || t < 0.1) {
            if (state.y < -0.5 && t > 0.1) break

            flightPath.add(FlightPoint(state.x / 3.0, state.y / 3.0, state.z / 3.0, t))
            if (state.y > maxY) maxY = state.y

            val k1 = deriv(state)
            val k2 = deriv(addDeriv(state, scaleDeriv(k1, dt / 2)))
            val k3 = deriv(addDeriv(state, scaleDeriv(k2, dt / 2)))
            val k4 = deriv(addDeriv(state, scaleDeriv(k3, dt)))

            prevVy = state.vy
            state = State(
                x    = state.x    + dt * (k1[0] + 2*k2[0] + 2*k3[0] + k4[0]) / 6,
                y    = state.y    + dt * (k1[1] + 2*k2[1] + 2*k3[1] + k4[1]) / 6,
                z    = state.z    + dt * (k1[2] + 2*k2[2] + 2*k3[2] + k4[2]) / 6,
                vx   = state.vx   + dt * (k1[3] + 2*k2[3] + 2*k3[3] + k4[3]) / 6,
                vy   = state.vy   + dt * (k1[4] + 2*k2[4] + 2*k3[4] + k4[4]) / 6,
                vz   = state.vz   + dt * (k1[5] + 2*k2[5] + 2*k3[5] + k4[5]) / 6,
                spin    = state.spin    * SPIN_DECAY.pow(dt),
                sidespin = state.sidespin * SPIN_DECAY.pow(dt)
            )
            t += dt
            if (t > 60.0) break  // safety
        }

        // Landing velocity for land angle
        landVy = state.vy
        landVz = state.vz

        val carryFt   = state.z.coerceAtLeast(0.0)
        val carryYards = carryFt / 3.0
        val offlineFt  = state.x  // negative = left
        val offlineFeet = offlineFt

        val roll       = estimateRoll(metrics, carryYards)
        val totalYards = carryYards + roll

        val maxHeightFeet = maxY

        // Land angle (degrees): angle below horizontal at landing
        val landSpeed = sqrt(landVy * landVy + landVz * landVz).coerceAtLeast(1.0)
        val landAngleDeg = Math.toDegrees(atan2(-landVy, landVz)).coerceIn(0.0, 90.0)

        val shotShape  = determineShotShape(metrics)
        val landingZone = determineLandingZone(abs(offlineFeet), club, totalYards)

        return ShotResult(
            carryYards       = carryYards,
            totalYards       = totalYards,
            offlineFeet      = offlineFeet / 3.0,   // convert ft → yards-equivalent feet for display
            maxHeightFeet    = maxHeightFeet / 3.0, // convert ft → yards (height in feet)
            timeOfFlightSecs = t,
            flightPath       = flightPath,
            landingZone      = landingZone,
            spinRate         = metrics.spinRpm,
            shotShape        = shotShape,
            landAngleDeg     = landAngleDeg
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Convert camera ShotDataSnapshot → SwingMetrics → ShotResult
    // ─────────────────────────────────────────────────────────────────────────
    fun metricsFromSnapshot(snapshot: ShotDataSnapshot, club: ClubType): SwingMetrics {
        if (snapshot.ballSpeedMph < 5.0) return generateDefaultMetrics(club)

        val smash = smashFactor(club)
        val clubSpeed = (snapshot.ballSpeedMph / smash).coerceIn(5.0, 160.0)

        val backspinRpm = snapshot.backspinRpm.coerceIn(0.0, 12000.0)
        val sidespinRpm = snapshot.sidespinRpm.coerceIn(-4000.0, 4000.0)
        val totalSpin   = sqrt(backspinRpm * backspinRpm + sidespinRpm * sidespinRpm)

        // If camera gave us low spin, augment with club-typical values
        val blendedSpin = if (backspinRpm < 100.0)
            calculateSpin(club, clubSpeed, snapshot.launchAngleDeg, snapshot.faceAngleDeg)
        else totalSpin

        return SwingMetrics(
            clubHeadSpeedMph    = clubSpeed,
            ballSpeedMph        = snapshot.ballSpeedMph,
            smashFactor         = smash,
            launchAngleDegrees  = snapshot.launchAngleDeg,
            horizontalLaunchDeg = snapshot.horizontalLaunchDeg,
            spinRpm             = blendedSpin,
            backspinRpm         = if (backspinRpm > 100.0) backspinRpm else blendedSpin * 0.92,
            sidespinRpm         = sidespinRpm,
            spinAxisDeg         = snapshot.spinAxisDeg,
            swingPathDegrees    = snapshot.swingPathDeg,
            faceAngleDegrees    = snapshot.faceAngleDeg,
            dynamicLoftDeg      = snapshot.dynamicLoftDeg,
            attackAngleDeg      = snapshot.attackAngleDeg,
            attackAngleDegrees  = snapshot.attackAngleDeg,
            confidence          = if (snapshot.ballSpeedMph > 20.0) 0.85f else 0.55f
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Legacy: generate metrics from raw BallPosition list (fallback path)
    // ─────────────────────────────────────────────────────────────────────────
    fun generateMetricsFromTracking(
        ballPositions: List<com.golfsim.app.models.BallPosition>,
        club: ClubType,
        screenWidthPx: Int,
        screenHeightPx: Int,
        frameRateFps: Double = 60.0
    ): SwingMetrics {
        if (ballPositions.size < 3) return generateDefaultMetrics(club)

        // Linear regression on last 10 positions for velocity
        val recent = ballPositions.takeLast(10)
        val t0 = recent.first().timestamp
        val tList = recent.map { (it.timestamp - t0) / 1000.0 }
        val xList = recent.map { it.x.toDouble() }
        val yList = recent.map { it.y.toDouble() }

        val tMean = tList.average(); val xMean = xList.average(); val yMean = yList.average()
        var tVar = 0.0; var txCov = 0.0; var tyCov = 0.0
        for (i in tList.indices) {
            val dt = tList[i] - tMean
            tVar += dt * dt; txCov += dt * (xList[i] - xMean); tyCov += dt * (yList[i] - yMean)
        }
        if (tVar < 1e-9) return generateDefaultMetrics(club)

        val vxPxSec = txCov / tVar
        val vyPxSec = tyCov / tVar

        // Pixel → feet using FOV geometry
        val feetPerPx = (2.0 * 10.0 * tan(Math.toRadians(38.5))) / screenWidthPx
        val speedXFps =  abs(vxPxSec) * feetPerPx
        val speedYFps = -vyPxSec * feetPerPx   // invert screen Y
        val speedFps  = sqrt(speedXFps * speedXFps + speedYFps * speedYFps)
        val ballSpeedMph = (speedFps / 1.46667).coerceIn(10.0, 220.0)

        val launchAngle = Math.toDegrees(atan2(speedYFps, speedXFps)).coerceIn(0.0, 55.0)
        val swingPath   = 0.0  // cannot determine from 2D without more data
        val faceAngle   = swingPath * 0.6
        val smash       = smashFactor(club)
        val clubSpeed   = ballSpeedMph / smash
        val spin        = calculateSpin(club, clubSpeed, launchAngle, faceAngle)
        val sidespin    = faceAngle * 150.0

        return SwingMetrics(
            clubHeadSpeedMph   = clubSpeed,
            ballSpeedMph       = ballSpeedMph,
            smashFactor        = smash,
            launchAngleDegrees = launchAngle,
            spinRpm            = spin,
            backspinRpm        = spin * 0.92,
            sidespinRpm        = sidespin,
            swingPathDegrees   = swingPath,
            faceAngleDegrees   = faceAngle,
            attackAngleDegrees = if (club == ClubType.DRIVER) 3.0 else -4.0,
            confidence         = 0.70f
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Simulated (random) metrics for "Simulate" mode
    // ─────────────────────────────────────────────────────────────────────────
    fun generateDefaultMetrics(club: ClubType): SwingMetrics {
        val variation  = (Math.random() - 0.5) * 10
        val clubSpeed  = (club.maxSpeedMph * 0.88 + variation).coerceAtLeast(20.0)
        val smash      = smashFactor(club)
        val ballSpeed  = clubSpeed * smash
        val launchAngle = (club.loftDegrees * 0.65 + (Math.random() - 0.5) * 3).coerceIn(5.0, 45.0)
        val swingPath  = (Math.random() - 0.5) * 4.0
        val faceAngle  = swingPath * 0.6 + (Math.random() - 0.5) * 2.0
        val spin       = calculateSpin(club, clubSpeed, launchAngle, faceAngle)
        val sidespin   = faceAngle * 120.0
        val spinAxis   = Math.toDegrees(atan2(sidespin, spin))

        return SwingMetrics(
            clubHeadSpeedMph    = clubSpeed,
            ballSpeedMph        = ballSpeed,
            smashFactor         = smash,
            launchAngleDegrees  = launchAngle,
            horizontalLaunchDeg = faceAngle * 0.75,
            spinRpm             = spin,
            backspinRpm         = spin * 0.92,
            sidespinRpm         = sidespin,
            spinAxisDeg         = spinAxis,
            swingPathDegrees    = swingPath,
            faceAngleDegrees    = faceAngle,
            dynamicLoftDeg      = launchAngle + spin / 3000.0,
            attackAngleDeg      = if (club == ClubType.DRIVER) 3.0 else -4.0,
            attackAngleDegrees  = if (club == ClubType.DRIVER) 3.0 else -4.0,
            confidence          = 0.92f
        )
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private fun smashFactor(club: ClubType): Double = when {
        club.loftDegrees < 12  -> 1.48
        club.loftDegrees < 20  -> 1.44
        club.loftDegrees < 35  -> 1.38
        else                   -> 1.25
    }

    private fun calculateSpin(club: ClubType, clubSpeed: Double, launchAngle: Double, faceAngle: Double): Double {
        val baseSpin = when {
            club.loftDegrees < 12  -> 2400.0
            club.loftDegrees < 20  -> 3500.0
            club.loftDegrees < 30  -> 4800.0
            club.loftDegrees < 40  -> 6500.0
            club.loftDegrees < 50  -> 8000.0
            else                   -> 9500.0
        }
        val speedFactor   = clubSpeed / club.maxSpeedMph
        val faceSpinAdder = abs(faceAngle) * 200.0
        return (baseSpin * speedFactor + faceSpinAdder).coerceIn(500.0, 14000.0)
    }

    private fun deriv(s: State): DoubleArray {
        val speed = sqrt(s.vx * s.vx + s.vy * s.vy + s.vz * s.vz)
        if (speed < 0.1) return doubleArrayOf(s.vx, s.vy, s.vz, 0.0, -G, 0.0)

        val rho = AIR_DENSITY / BALL_MASS
        val drag = 0.5 * AIR_DENSITY * BALL_AREA * CD_BASE * speed / BALL_MASS

        val spinFactor   = s.spin    / 10000.0
        val sideFactor   = s.sidespin / 10000.0
        val clBack  = CL_MAX * tanh(spinFactor)
        val clSide  = CL_MAX * tanh(sideFactor) * 0.5

        // Lift (vertical) from backspin
        val liftY   = 0.5 * AIR_DENSITY * BALL_AREA * clBack * speed * speed / BALL_MASS

        // Side force from sidespin
        val sideF   = 0.5 * AIR_DENSITY * BALL_AREA * clSide * speed * speed / BALL_MASS

        val ax = -drag * s.vx / speed + sideF
        val ay = -drag * s.vy / speed + liftY - G
        val az = -drag * s.vz / speed

        return doubleArrayOf(s.vx, s.vy, s.vz, ax, ay, az)
    }

    private fun addDeriv(s: State, d: DoubleArray) = State(
        s.x + d[0], s.y + d[1], s.z + d[2],
        s.vx + d[3], s.vy + d[4], s.vz + d[5],
        s.spin, s.sidespin
    )

    private fun scaleDeriv(d: DoubleArray, scale: Double): DoubleArray = d.map { it * scale }.toDoubleArray()

    private fun estimateRoll(metrics: SwingMetrics, carryYards: Double): Double {
        val rollFactor = when {
            metrics.launchAngleDegrees > 30 -> 0.05
            metrics.launchAngleDegrees > 20 -> 0.10
            metrics.launchAngleDegrees > 12 -> 0.15
            else                            -> 0.20
        }
        return carryYards * rollFactor
    }

    private fun determineShotShape(m: SwingMetrics): ShotShape {
        val face = m.faceAngleDegrees
        val path = m.swingPathDegrees
        return when {
            abs(face) < 1.5 && abs(path) < 2.0 -> ShotShape.STRAIGHT
            face < -3 && path < -2              -> ShotShape.PULL
            face > 3  && path > 2               -> ShotShape.PUSH
            face < -2                           -> ShotShape.HOOK
            face > 2                            -> ShotShape.SLICE
            path < -1.5 && face > path          -> ShotShape.FADE
            path > 1.5  && face < path          -> ShotShape.DRAW
            path > 1.5  && face < 0             -> ShotShape.PUSH_DRAW
            path < -1.5 && face > 0             -> ShotShape.PULL_FADE
            else                                -> ShotShape.STRAIGHT
        }
    }

    private fun determineLandingZone(absOfflineFeet: Double, club: ClubType, totalYards: Double): LandingZone {
        return when {
            absOfflineFeet > 80 -> LandingZone.OOB
            absOfflineFeet > 60 -> LandingZone.WATER
            absOfflineFeet > 35 -> LandingZone.ROUGH
            absOfflineFeet > 20 && club == ClubType.PUTTER -> LandingZone.ROUGH
            totalYards > 250 && absOfflineFeet < 15 -> LandingZone.FAIRWAY
            else                -> LandingZone.FAIRWAY
        }
    }
}
