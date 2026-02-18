package com.golfsim.app.models

import kotlin.math.*

// ─── Golf Club Data ───────────────────────────────────────────────────────────
enum class ClubType(
    val displayName: String,
    val loftDegrees: Double,
    val avgCarryYards: Double,
    val maxSpeedMph: Double,
    val icon: String
) {
    DRIVER("Driver", 10.5, 230.0, 113.0, "🏌️"),
    WOOD_3("3 Wood", 15.0, 210.0, 107.0, "🪵"),
    WOOD_5("5 Wood", 18.0, 195.0, 103.0, "🪵"),
    HYBRID("Hybrid", 22.0, 185.0, 99.0, "⛳"),
    IRON_4("4 Iron", 25.0, 170.0, 96.0, "🔩"),
    IRON_5("5 Iron", 28.0, 160.0, 93.0, "🔩"),
    IRON_6("6 Iron", 31.0, 150.0, 90.0, "🔩"),
    IRON_7("7 Iron", 34.0, 140.0, 87.0, "🔩"),
    IRON_8("8 Iron", 37.0, 130.0, 83.0, "🔩"),
    IRON_9("9 Iron", 41.0, 120.0, 79.0, "🔩"),
    PITCHING_WEDGE("PW", 46.0, 105.0, 74.0, "⛳"),
    GAP_WEDGE("GW", 50.0, 95.0, 70.0, "⛳"),
    SAND_WEDGE("SW", 56.0, 80.0, 66.0, "⛳"),
    LOB_WEDGE("LW", 60.0, 65.0, 62.0, "⛳"),
    PUTTER("Putter", 4.0, 0.0, 5.0, "🏒")
}

// ─── Ball Tracking ────────────────────────────────────────────────────────────
data class BallPosition(
    val x: Float,   // pixels on screen
    val y: Float,
    val timestamp: Long
)

data class SwingMetrics(
    val clubHeadSpeedMph: Double,
    val ballSpeedMph: Double,
    val launchAngleDegrees: Double,
    val swingPathDegrees: Double,   // -left to +right (in-to-out positive)
    val faceAngleDegrees: Double,   // -open to +closed
    val smashFactor: Double,        // ball speed / club speed ratio
    val attackAngleDegrees: Double, // downward negative, upward positive
    val spinRpm: Double,
    val sidespin: Double,
    val confidence: Float           // 0.0 to 1.0 detection confidence
) {
    companion object {
        fun empty() = SwingMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0f)
    }
}

// ─── Ball Flight Physics ──────────────────────────────────────────────────────
data class FlightPoint(
    val x: Double,   // yards lateral (negative = left)
    val y: Double,   // yards height
    val z: Double,   // yards carry distance
    val timeSeconds: Double
)

data class ShotResult(
    val carryYards: Double,
    val totalYards: Double,
    val offlineFeet: Double,      // negative = left
    val maxHeightFeet: Double,
    val timeOfFlightSecs: Double,
    val flightPath: List<FlightPoint>,
    val landingZone: LandingZone,
    val spinRate: Double,
    val shotShape: ShotShape
)

enum class LandingZone {
    FAIRWAY, ROUGH, BUNKER, WATER, OOB, GREEN, CUP
}

enum class ShotShape(val displayName: String) {
    STRAIGHT("Straight"),
    DRAW("Draw"),
    FADE("Fade"),
    HOOK("Hook"),
    SLICE("Slice"),
    PUSH("Push"),
    PULL("Pull"),
    PUSH_DRAW("Push Draw"),
    PULL_FADE("Pull Fade")
}

// ─── Course Data ──────────────────────────────────────────────────────────────
data class Hole(
    val number: Int,
    val par: Int,
    val yardageTees: Map<TeeBox, Int>,
    val handicapIndex: Int,
    val description: String,
    val fairwayWidthYards: Double,
    val greenSizeSqFt: Double,
    val hazards: List<Hazard>
)

data class Hazard(
    val type: HazardType,
    val startYards: Double,
    val endYards: Double,
    val leftOfflineFeet: Double,
    val rightOfflineFeet: Double
)

enum class HazardType { WATER, BUNKER, ROUGH, OOB, TREES }

enum class TeeBox(val color: String, val handicap: String) {
    BLACK("Black", "Scratch/Pro"),
    BLUE("Blue", "0-5"),
    WHITE("White", "6-15"),
    GOLD("Gold", "Senior"),
    RED("Red", "Forward")
}

// ─── Courses ──────────────────────────────────────────────────────────────────
object CourseDatabase {
    val PEBBLE_BEACH = Course(
        name = "Pebble Beach Golf Links",
        location = "Pebble Beach, CA",
        rating = 75.5,
        slope = 145,
        holes = generatePebbleBeachHoles()
    )

    val ST_ANDREWS = Course(
        name = "St Andrews Old Course",
        location = "St Andrews, Scotland",
        rating = 73.1,
        slope = 132,
        holes = generateStAndrewsHoles()
    )

    val AUGUSTA = Course(
        name = "Augusta National",
        location = "Augusta, GA",
        rating = 78.1,
        slope = 148,
        holes = generateAugustaHoles()
    )

    val DRIVING_RANGE = Course(
        name = "Practice Range",
        location = "Your Location",
        rating = 72.0,
        slope = 113,
        holes = generateDrivingRangeHoles()
    )

    val ALL_COURSES = listOf(DRIVING_RANGE, PEBBLE_BEACH, ST_ANDREWS, AUGUSTA)
}

data class Course(
    val name: String,
    val location: String,
    val rating: Double,
    val slope: Int,
    val holes: List<Hole>
) {
    val totalYardage: Int get() = holes.sumOf { it.yardageTees[TeeBox.WHITE] ?: 0 }
    val par: Int get() = holes.sumOf { it.par }
}

private fun generatePebbleBeachHoles(): List<Hole> = listOf(
    Hole(1, 4, mapOf(TeeBox.BLACK to 381, TeeBox.WHITE to 328), 8, "Dogleg right opener", 38.0, 5800.0,
        listOf(Hazard(HazardType.BUNKER, 220.0, 260.0, -20.0, -5.0))),
    Hole(2, 5, mapOf(TeeBox.BLACK to 502, TeeBox.WHITE to 484), 16, "Long par 5 left", 40.0, 6200.0,
        listOf(Hazard(HazardType.ROUGH, 100.0, 350.0, -60.0, -40.0))),
    Hole(3, 4, mapOf(TeeBox.BLACK to 404, TeeBox.WHITE to 368), 12, "Slight dogleg right", 35.0, 5400.0, emptyList()),
    Hole(4, 4, mapOf(TeeBox.BLACK to 331, TeeBox.WHITE to 299), 18, "Short downhill", 42.0, 6000.0, emptyList()),
    Hole(5, 3, mapOf(TeeBox.BLACK to 195, TeeBox.WHITE to 166), 14, "Uphill par 3 to green", 30.0, 4800.0,
        listOf(Hazard(HazardType.BUNKER, 160.0, 180.0, -15.0, 15.0))),
    Hole(6, 5, mapOf(TeeBox.BLACK to 523, TeeBox.WHITE to 513), 2, "Downhill par 5 to ocean", 44.0, 7000.0,
        listOf(Hazard(HazardType.WATER, 400.0, 523.0, -100.0, 0.0))),
    Hole(7, 3, mapOf(TeeBox.BLACK to 107, TeeBox.WHITE to 100), 17, "Famous short par 3 to ocean", 25.0, 3200.0,
        listOf(Hazard(HazardType.WATER, 0.0, 107.0, -100.0, -60.0), Hazard(HazardType.BUNKER, 80.0, 110.0, 10.0, 30.0))),
    Hole(8, 4, mapOf(TeeBox.BLACK to 431, TeeBox.WHITE to 418), 4, "Blind tee shot cliff edge", 36.0, 5600.0,
        listOf(Hazard(HazardType.WATER, 0.0, 431.0, -80.0, -50.0))),
    Hole(9, 4, mapOf(TeeBox.BLACK to 481, TeeBox.WHITE to 452), 10, "Long par 4 along coast", 38.0, 6200.0, emptyList()),
    Hole(10, 4, mapOf(TeeBox.BLACK to 446, TeeBox.WHITE to 415), 6, "Downhill to ocean", 40.0, 5800.0,
        listOf(Hazard(HazardType.WATER, 300.0, 446.0, -100.0, -70.0))),
    Hole(11, 4, mapOf(TeeBox.BLACK to 390, TeeBox.WHITE to 370), 11, "Tricky mid-length", 36.0, 5400.0, emptyList()),
    Hole(12, 3, mapOf(TeeBox.BLACK to 202, TeeBox.WHITE to 187), 13, "Long par 3 oceanside", 28.0, 4400.0, emptyList()),
    Hole(13, 4, mapOf(TeeBox.BLACK to 445, TeeBox.WHITE to 412), 3, "Dogleg right cliff", 34.0, 5600.0, emptyList()),
    Hole(14, 5, mapOf(TeeBox.BLACK to 580, TeeBox.WHITE to 555), 1, "Long par 5 challenge", 42.0, 7200.0, emptyList()),
    Hole(15, 4, mapOf(TeeBox.BLACK to 397, TeeBox.WHITE to 382), 15, "Downhill approach", 38.0, 5800.0, emptyList()),
    Hole(16, 4, mapOf(TeeBox.BLACK to 403, TeeBox.WHITE to 388), 7, "Stunning coastal hole", 36.0, 5600.0, emptyList()),
    Hole(17, 3, mapOf(TeeBox.BLACK to 208, TeeBox.WHITE to 178), 9, "Island-like green", 26.0, 4000.0,
        listOf(Hazard(HazardType.WATER, 0.0, 208.0, -100.0, 100.0))),
    Hole(18, 5, mapOf(TeeBox.BLACK to 543, TeeBox.WHITE to 508), 5, "Iconic finishing hole along Stillwater Cove", 44.0, 7800.0,
        listOf(Hazard(HazardType.WATER, 0.0, 543.0, -100.0, -60.0)))
)

private fun generateStAndrewsHoles(): List<Hole> = (1..18).map { n ->
    val pars = listOf(4,4,4,4,5,4,4,3,4,4,3,4,4,5,4,4,4,4)
    val yards = listOf(376,453,397,480,568,412,372,166,352,380,174,348,465,614,455,424,495,354)
    Hole(n, pars[n-1], mapOf(TeeBox.BLACK to yards[n-1], TeeBox.WHITE to (yards[n-1]-30)), n, "St Andrews Hole $n", 45.0, 6200.0, emptyList())
}

private fun generateAugustaHoles(): List<Hole> = (1..18).map { n ->
    val pars = listOf(4,5,4,3,4,3,4,5,4,4,4,3,5,4,5,3,4,4)
    val yards = listOf(445,575,350,240,495,180,450,570,460,495,520,155,510,440,530,170,440,465)
    val names = listOf("Tea Olive","Pink Dogwood","Flowering Peach","Flowering Crab Apple","Magnolia","Juniper",
        "Pampas","Yellow Jasmine","Carolina Cherry","Camellia","White Dogwood","Golden Bell","Azalea",
        "Chinese Fir","Firethorn","Redbud","Nandina","Holly")
    Hole(n, pars[n-1], mapOf(TeeBox.BLACK to yards[n-1], TeeBox.WHITE to (yards[n-1]-40)), n, names[n-1], 40.0, 7000.0, emptyList())
}

private fun generateDrivingRangeHoles(): List<Hole> = listOf(
    Hole(1, 5, mapOf(TeeBox.BLACK to 300, TeeBox.WHITE to 300, TeeBox.RED to 200), 1,
        "Open Range - Hit it straight!", 100.0, 10000.0, emptyList())
)

// ─── Scorecard ────────────────────────────────────────────────────────────────
data class RoundScore(
    val courseId: String,
    val playerName: String,
    val teeBox: TeeBox,
    val date: Long = System.currentTimeMillis(),
    val holeScores: MutableList<HoleScore> = mutableListOf(),
    val id: String = java.util.UUID.randomUUID().toString()
) {
    val totalStrokes: Int get() = holeScores.sumOf { it.strokes }
    val totalPutts: Int get() = holeScores.sumOf { it.putts }
    val fairwaysHit: Int get() = holeScores.count { it.fairwayHit }
    val greensInRegulation: Int get() = holeScores.count { it.greenInRegulation }
    val relativeToPar: Int get() = holeScores.sumOf { it.relativeToPar }
}

data class HoleScore(
    val holeNumber: Int,
    val par: Int,
    val strokes: Int,
    val putts: Int,
    val fairwayHit: Boolean,
    val greenInRegulation: Boolean,
    val shots: List<ShotResult> = emptyList()
) {
    val relativeToPar: Int get() = strokes - par
    val scoreLabel: String get() = when (relativeToPar) {
        -3 -> "Albatross"
        -2 -> "Eagle"
        -1 -> "Birdie"
        0 -> "Par"
        1 -> "Bogey"
        2 -> "Double Bogey"
        3 -> "Triple Bogey"
        else -> "+$relativeToPar"
    }
}
