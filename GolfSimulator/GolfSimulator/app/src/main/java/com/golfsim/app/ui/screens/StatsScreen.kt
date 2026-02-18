package com.golfsim.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.golfsim.app.models.ShotShape
import com.golfsim.app.ui.GolfSimViewModel
import com.golfsim.app.ui.Screen
import com.golfsim.app.ui.theme.*
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(vm: GolfSimViewModel) {
    val stats = vm.getSessionStats()
    val rounds by vm.savedRounds.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Statistics", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { vm.navigateTo(Screen.HOME) }) {
                        Icon(Icons.Default.ArrowBack, null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = DarkBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (stats.totalShots == 0 && rounds.isEmpty()) {
                EmptyStatsState()
            } else {
                // Session stats
                if (stats.totalShots > 0) {
                    SessionStatsCard(stats)
                    ShotShapeChart(stats.shotShapeBreakdown)
                }

                // All-time stats from rounds
                if (rounds.isNotEmpty()) {
                    AllTimeStatsCard(rounds)
                    ScoreHistoryChart(rounds.take(10).reversed())
                }
            }
        }
    }
}

@Composable
fun EmptyStatsState() {
    Box(modifier = Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📊", fontSize = 64.sp)
            Spacer(Modifier.height(16.dp))
            Text("No stats yet!", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Hit some shots to see your statistics here.", color = Color(0xFF9E9E9E), textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun SessionStatsCard(stats: com.golfsim.app.ui.SessionStats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("SESSION", fontSize = 11.sp, color = Color(0xFF9E9E9E), letterSpacing = 2.sp)
            Text("${stats.totalShots} Shots", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.White)
            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SessionStat("${stats.avgCarry.toInt()}", "yds", "Avg Carry", GolfGreenLight)
                SessionStat("${stats.avgTotal.toInt()}", "yds", "Avg Total", Color.White)
                SessionStat("${stats.longestDrive.toInt()}", "yds", "Longest", GoldAccent)
            }

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SessionStat("${stats.avgOffline.toInt()}", "ft", "Avg Offline", Color(0xFFEF5350))
                SessionStat("${stats.fairwayPct.toInt()}", "%", "Fairways", GolfGreenLight)
            }
        }
    }
}

@Composable
fun SessionStat(value: String, unit: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 28.sp, fontWeight = FontWeight.Black, color = color)
            Text(unit, fontSize = 12.sp, color = Color(0xFF9E9E9E), modifier = Modifier.padding(bottom = 4.dp))
        }
        Text(label, fontSize = 11.sp, color = Color(0xFF9E9E9E))
    }
}

@Composable
fun ShotShapeChart(breakdown: Map<ShotShape, Int>) {
    if (breakdown.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("SHOT SHAPES", fontSize = 11.sp, color = Color(0xFF9E9E9E), letterSpacing = 2.sp)
            Spacer(Modifier.height(12.dp))

            val total = breakdown.values.sum().toFloat()
            val sorted = breakdown.entries.sortedByDescending { it.value }

            sorted.forEach { (shape, count) ->
                val pct = count / total
                val shapeColor = when (shape) {
                    ShotShape.STRAIGHT -> GolfGreenLight
                    ShotShape.DRAW, ShotShape.PUSH_DRAW -> Color(0xFF42A5F5)
                    ShotShape.FADE, ShotShape.PULL_FADE -> GoldAccent
                    ShotShape.HOOK -> Color(0xFFAB47BC)
                    ShotShape.SLICE -> Color(0xFFEF5350)
                    else -> Color(0xFF9E9E9E)
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(shape.displayName, fontSize = 13.sp, color = Color.White, modifier = Modifier.width(90.dp))
                    Box(modifier = Modifier.weight(1f).height(16.dp).background(Color(0xFF1A2E1A), RoundedCornerShape(8.dp))) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(pct)
                                .fillMaxHeight()
                                .background(shapeColor, RoundedCornerShape(8.dp))
                        )
                    }
                    Text("$count", fontSize = 13.sp, color = shapeColor, fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(30.dp), textAlign = TextAlign.End)
                }
            }
        }
    }
}

@Composable
fun AllTimeStatsCard(rounds: List<com.golfsim.app.models.RoundScore>) {
    val avgScore = rounds.map { it.relativeToPar }.average()
    val bestScore = rounds.minOfOrNull { it.relativeToPar } ?: 0
    val totalRounds = rounds.size
    val avgFairways = rounds.map { it.fairwaysHit }.average()
    val avgGIR = rounds.map { it.greensInRegulation }.average()
    val avgPutts = rounds.map { it.totalPutts }.average()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("ALL-TIME STATS", fontSize = 11.sp, color = Color(0xFF9E9E9E), letterSpacing = 2.sp)
            Text("$totalRounds Rounds Played", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.White)
            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val avgColor = if (avgScore <= 0) GolfGreenLight else Color(0xFFEF5350)
                SessionStat("${if (avgScore >= 0) "+" else ""}${String.format("%.1f", avgScore)}", "", "Avg Score", avgColor)
                val bestColor = if (bestScore <= 0) GolfGreenLight else Color.White
                SessionStat("${if (bestScore >= 0) "+" else ""}$bestScore", "", "Best", bestColor)
                SessionStat("${avgPutts.toInt()}", "", "Avg Putts", SkyBlue)
            }

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SessionStat("${avgFairways.toInt()}", "", "Avg FIR", GolfGreenLight)
                SessionStat("${avgGIR.toInt()}", "", "Avg GIR", GolfGreenLight)
            }
        }
    }
}

@Composable
fun ScoreHistoryChart(rounds: List<com.golfsim.app.models.RoundScore>) {
    if (rounds.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("SCORE TREND", fontSize = 11.sp, color = Color(0xFF9E9E9E), letterSpacing = 2.sp)
            Spacer(Modifier.height(12.dp))

            rounds.forEach { round ->
                val color = when {
                    round.relativeToPar < 0 -> GolfGreenLight
                    round.relativeToPar == 0 -> GoldAccent
                    else -> Color(0xFFEF5350)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(round.courseId.take(22), fontSize = 13.sp, color = Color.White)
                        Text(
                            java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(round.date)),
                            fontSize = 11.sp, color = Color(0xFF9E9E9E)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${round.totalStrokes}", fontSize = 16.sp, color = Color.White)
                        Text(
                            "${if (round.relativeToPar >= 0) "+" else ""}${round.relativeToPar}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = color
                        )
                    }
                }
            }
        }
    }
}
