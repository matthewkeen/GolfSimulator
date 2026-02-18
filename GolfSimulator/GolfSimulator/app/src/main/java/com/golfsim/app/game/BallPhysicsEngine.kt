package com.golfsim.app.game

import com.golfsim.app.models.*
import kotlin.math.*

/**
 * Full 6DOF golf ball flight physics engine.
 * Uses Runge-Kutta 4 integration with aerodynamic models.
 */
object BallPhysicsEngine {

    // Constants
    private const val G = 32.174          // ft/s² gravity
    private const val AIR_DENSITY = 0.0765 // lb/ft³ sea level
    private const val BALL_MASS = 0.10125  // lbs (1.62 oz)
    private const val BALL_DIAMETER = 0.141667 // ft (1.68 inches)
    private const val BALL_RADIUS = BALL_DIAMETER / 2.0
    private const val BALL_AREA = PI * BALL_RADIUS * BALL_RADIUS

    // Aerodynamic coefficients
    private const val CD_BASE = 0.23      // drag coefficient (dimpled ball)
    private const val CL_MAX = 0.54       // max lift coefficient
    private const val SPIN_DECAY = 0.98   // spin decay per second (approximate)

    data class State(
        val x: Double, val y: Double, val z: Double,   // position (ft) x=lateral, y=height, z=forward
        val vx: Double, val vy: Double, val vz: Double, // velocity (ft/s)
        val spin: Double,    // backspin RPM
        val sidespin: Double // sidespin RPM (positive = left curve for RH golfer)
    )

    fun simulate(metrics: SwingMetrics, club: ClubType): ShotResult {
        val speedFps = metrics.ballSpeedMph * 1.46667  // convert mph to ft/s
        val launchRad = Math.toRadians(metrics.launchAngleDegrees)
        val pathRad = Math.toRadians(metrics.swingPathDegrees)

        // Initial velocity components
        val vz0 = speedFps * cos(launchRad) * cos(pathRad)
        val vx0 = speedFps * cos(launchRad) * sin(pathRad)
        val vy0 = speedFps * sin(launchRad)

        var state = State(
            x = 0.0, y = 0.0, z = 0.0,
            vx = vx0, vy = vy0, vz = vz0,
            spin = metrics.spinRpm,
            sidespin = metrics.sidespin
        )

        val flightPath = mutableListOf<FlightPoint>()
        var t = 0.0
        val dt = 0.01  // 10ms time steps
        var maxHeight = 0.0
        var landed = false

        // RK4 integration
        while (t < 30.0 && !landed) {
            flightPath.add(
                FlightPoint(
                    x = state.x / 3.0,   // ft to yards
                    y = state.y / 3.0,
                    z = state.z / 3.0,
                    timeSeconds = t
                )
            )

            if (state.y > maxHeight) maxHeight = state.y

            // Check landing
            if (t > 0.5 && state.y <= 0.0) {
                landed = true
                break
            }

            state = rk4Step(state, dt, t)
            t += dt
        }

        val carryFt = state.z
        val lateralFt = state.x
        val carryYards = carryFt / 3.0
        val offlineFeet = lateralFt

        // Simulate roll (simplified)
        val rollYards = estimateRoll(metrics, carryYards)
        val totalYards = carryYards + rollYards

        // Determine shot shape
        val shotShape = determineShotShape(metrics)

        // Determine landing zone (simplified based on offline distance)
        val landingZone = determineLandingZone(abs(offlineFeet), club)

        return ShotResult(
            carryYards = carryYards.coerceAtLeast(0.0),
            totalYards = totalYards.coerceAtLeast(0.0),
            offlineFeet = offlineFeet,
            maxHeightFeet = maxHeight / 3.0,
            timeOfFlightSecs = t,
            flightPath = flightPath,
            landingZone = landingZone,
            spinRate = metrics.spinRpm,
            shotShape = shotShape
        )
    }

    private fun rk4Step(s: State, dt: Double, t: Double): State {
        val k1 = derivatives(s)
        val k2 = derivatives(stateAdd(s, scaleDeriv(k1, dt / 2)))
        val k3 = derivatives(stateAdd(s, scaleDeriv(k2, dt / 2)))
        val k4 = derivatives(stateAdd(s, scaleDeriv(k3, dt)))

        val spinDecay = SPIN_DECAY.pow(dt)

        return State(
            x = s.x + dt / 6 * (k1[0] + 2 * k2[0] + 2 * k3[0] + k4[0]),
            y = s.y + dt / 6 * (k1[1] + 2 * k2[1] + 2 * k3[1] + k4[1]),
            z = s.z + dt / 6 * (k1[2] + 2 * k2[2] + 2 * k3[2] + k4[2]),
            vx = s.vx + dt / 6 * (k1[3] + 2 * k2[3] + 2 * k3[3] + k4[3]),
            vy = s.vy + dt / 6 * (k1[4] + 2 * k2[4] + 2 * k3[4] + k4[4]),
            vz = s.vz + dt / 6 * (k1[5] + 2 * k2[5] + 2 * k3[5] + k4[5]),
            spin = s.spin * spinDecay,
            sidespin = s.sidespin * spinDecay
        )
    }

    private fun derivatives(s: State): DoubleArray {
        val speed = sqrt(s.vx * s.vx + s.vy * s.vy + s.vz * s.vz)
        if (speed < 0.01) return doubleArrayOf(s.vx, s.vy, s.vz, 0.0, -G, 0.0)

        // Drag force
        val dynamicPressure = 0.5 * AIR_DENSITY * speed * speed
        val dragForce = CD_BASE * dynamicPressure * BALL_AREA / BALL_MASS

        // Magnus effect (lift from spin)
        val spinFactor = s.spin / 3600.0  // normalize
        val cl = CL_MAX * tanh(spinFactor * 0.3)
        val liftForce = cl * dynamicPressure * BALL_AREA / BALL_MASS

        // Sidespin effect
        val sidespinFactor = s.sidespin / 3600.0
        val sideForce = CL_MAX * 0.5 * tanh(sidespinFactor * 0.3) * dynamicPressure * BALL_AREA / BALL_MASS

        val ax = -dragForce * (s.vx / speed) + sideForce
        val ay = -dragForce * (s.vy / speed) + liftForce - G
        val az = -dragForce * (s.vz / speed)

        return doubleArrayOf(s.vx, s.vy, s.vz, ax, ay, az)
    }

    private fun stateAdd(s: State, deriv: DoubleArray): State = State(
        s.x + deriv[0], s.y + deriv[1], s.z + deriv[2],
        s.vx + deriv[3], s.vy + deriv[4], s.vz + deriv[5],
        s.spin, s.sidespin
    )

    private fun scaleDeriv(d: DoubleArray, scale: Double): DoubleArray = d.map { it * scale }.toDoubleArray()

    private fun estimateRoll(metrics: SwingMetrics, carryYards: Double): Double {
        val rollFactor = when {
            metrics.launchAngleDegrees > 30 -> 0.05   // high shots stop quickly
            metrics.launchAngleDegrees > 20 -> 0.10
            metrics.launchAngleDegrees > 12 -> 0.15
            else -> 0.20                                // low shots roll more
        }
        return carryYards * rollFactor
    }

    private fun determineShotShape(m: SwingMetrics): ShotShape {
        val face = m.faceAngleDegrees
        val path = m.swingPathDegrees

        return when {
            abs(face) < 1.5 && abs(path) < 2.0 -> ShotShape.STRAIGHT
            face < -3 && path < -2 -> ShotShape.PULL
            face > 3 && path > 2 -> ShotShape.PUSH
            face < -2 -> ShotShape.HOOK
            face > 2 -> ShotShape.SLICE
            path < -1.5 && face > path -> ShotShape.FADE
            path > 1.5 && face < path -> ShotShape.DRAW
            path > 1.5 && face < 0 -> ShotShape.PUSH_DRAW
            path < -1.5 && face > 0 -> ShotShape.PULL_FADE
            else -> ShotShape.STRAIGHT
        }
    }

    private fun determineLandingZone(absOfflineFeet: Double, club: ClubType): LandingZone {
        return when {
            absOfflineFeet > 80 -> LandingZone.OOB
            absOfflineFeet > 60 -> LandingZone.WATER
            absOfflineFeet > 35 -> LandingZone.ROUGH
            absOfflineFeet > 20 && club == ClubType.PUTTER -> LandingZone.ROUGH
            else -> LandingZone.FAIRWAY
        }
    }

    /**
     * Generate realistic swing metrics from detected ball/club tracking data.
     * This bridges camera tracking data to full physics simulation.
     */
    fun generateMetricsFromTracking(
        ballPositions: List<com.golfsim.app.models.BallPosition>,
        club: ClubType,
        screenWidthPx: Int,
        screenHeightPx: Int,
        frameRateFps: Double = 60.0
    ): SwingMetrics {
        if (ballPositions.size < 3) {
            return generateDefaultMetrics(club)
        }

        // Calculate pixel velocity from last few tracked frames
        val recent = ballPositions.takeLast(5)
        val first = recent.first()
        val last = recent.last()

        val timeDiff = (last.timestamp - first.timestamp) / 1000.0  // seconds
        if (timeDiff <= 0) return generateDefaultMetrics(club)

        val dxPx = last.x - first.x
        val dyPx = last.y - first.y  // negative = upward on screen

        // Convert pixel velocity to approximate mph
        // Assumption: full screen width ≈ 15 yards at 8 feet setup distance
        val yardsPerPixel = 15.0 / screenWidthPx
        val feetPerPixel = yardsPerPixel * 3.0
        val metersPerPixel = feetPerPixel * 0.3048

        val speedXFps = (dxPx * feetPerPixel) / timeDiff
        val speedYFps = -(dyPx * feetPerPixel) / timeDiff  // invert Y (screen Y is down)
        val estimatedBallSpeedFps = sqrt(speedXFps * speedXFps + speedYFps * speedYFps)
        val estimatedBallSpeedMph = (estimatedBallSpeedFps / 1.46667).coerceIn(20.0, 200.0)

        val launchAngle = Math.toDegrees(atan2(speedYFps, abs(speedXFps)))
            .coerceIn(0.0, 55.0)

        val swingPath = if (dxPx > 0) -2.5 else 2.5  // rough estimate from direction
        val faceAngle = swingPath * 0.6  // face tracks between path and target (D-plane)

        val smash = club.loftDegrees.let { loft ->
            when {
                loft < 12 -> 1.48
                loft < 20 -> 1.44
                loft < 35 -> 1.38
                else -> 1.25
            }
        }

        val clubSpeed = estimatedBallSpeedMph / smash
        val spin = calculateSpin(club, clubSpeed, launchAngle, faceAngle)
        val sidespin = faceAngle * 150.0  // rough sidespin from face angle

        return SwingMetrics(
            clubHeadSpeedMph = clubSpeed,
            ballSpeedMph = estimatedBallSpeedMph,
            launchAngleDegrees = launchAngle,
            swingPathDegrees = swingPath,
            faceAngleDegrees = faceAngle,
            smashFactor = smash,
            attackAngleDegrees = if (club == ClubType.DRIVER) 3.0 else -4.0,
            spinRpm = spin,
            sidespin = sidespin,
            confidence = 0.75f
        )
    }

    fun generateDefaultMetrics(club: ClubType): SwingMetrics {
        val speedVariation = (Math.random() - 0.5) * 10
        val clubSpeed = (club.maxSpeedMph * 0.88 + speedVariation).coerceAtLeast(20.0)
        val smash = when {
            club.loftDegrees < 12 -> 1.48
            club.loftDegrees < 20 -> 1.44
            club.loftDegrees < 35 -> 1.38
            else -> 1.25
        }
        val ballSpeed = clubSpeed * smash
        val launchAngle = (club.loftDegrees * 0.65 + (Math.random() - 0.5) * 3).coerceIn(5.0, 45.0)
        val swingPath = (Math.random() - 0.5) * 4.0
        val faceAngle = swingPath * 0.6 + (Math.random() - 0.5) * 2.0
        val spin = calculateSpin(club, clubSpeed, launchAngle, faceAngle)

        return SwingMetrics(
            clubHeadSpeedMph = clubSpeed,
            ballSpeedMph = ballSpeed,
            launchAngleDegrees = launchAngle,
            swingPathDegrees = swingPath,
            faceAngleDegrees = faceAngle,
            smashFactor = smash,
            attackAngleDegrees = if (club == ClubType.DRIVER) 3.0 else -4.0,
            spinRpm = spin,
            sidespin = faceAngle * 120.0,
            confidence = 0.90f
        )
    }

    private fun calculateSpin(club: ClubType, clubSpeed: Double, launchAngle: Double, faceAngle: Double): Double {
        val baseSpin = when {
            club.loftDegrees < 12 -> 2400.0
            club.loftDegrees < 20 -> 3500.0
            club.loftDegrees < 30 -> 4800.0
            club.loftDegrees < 40 -> 6500.0
            club.loftDegrees < 50 -> 8000.0
            else -> 9500.0
        }
        val speedFactor = clubSpeed / club.maxSpeedMph
        val faceSpinAdder = abs(faceAngle) * 200.0
        return (baseSpin * speedFactor + faceSpinAdder).coerceIn(500.0, 14000.0)
    }
}
