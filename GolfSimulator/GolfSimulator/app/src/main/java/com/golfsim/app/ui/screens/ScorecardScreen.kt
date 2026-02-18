package com.golfsim.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.golfsim.app.models.RoundScore
import com.golfsim.app.ui.GolfSimViewModel
import com.golfsim.app.ui.Screen
import com.golfsim.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScorecardScreen(vm: GolfSimViewModel) {
    val rounds by vm.savedRounds.collectAsState()
    val currentRound by vm.currentRound.collectAsState()
    var selectedRound by remember { mutableStateOf<RoundScore?>(currentRound) }

    if (selectedRound != null) {
        RoundDetailScreen(
            round = selectedRound!!,
            onBack = { selectedRound = null }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Scorecards", fontWeight = FontWeight.Bold) },
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
            if (rounds.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📋", fontSize = 64.sp)
                        Spacer(Modifier.height(16.dp))
                        Text("No rounds yet", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Complete a round to see your scorecard.", color = Color(0xFF9E9E9E))
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(rounds) { round ->
                        RoundSummaryCard(round = round, onClick = { selectedRound = round })
                    }
                }
            }
        }
    }
}

@Composable
fun RoundSummaryCard(round: RoundScore, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(round.courseId, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                    Text(round.playerName, color = Color(0xFF9E9E9E), fontSize = 13.sp)
                    Text(
                        java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.US).format(java.util.Date(round.date)),
                        color = Color(0xFF9E9E9E), fontSize = 12.sp
                    )
                }
                val scoreColor = when {
                    round.relativeToPar < 0 -> GolfGreenLight
                    round.relativeToPar == 0 -> GoldAccent
                    else -> Color(0xFFEF5350)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${if (round.relativeToPar >= 0) "+" else ""}${round.relativeToPar}",
                        fontSize = 28.sp, fontWeight = FontWeight.Black, color = scoreColor
                    )
                    Text("${round.totalStrokes} strokes", fontSize = 12.sp, color = Color(0xFF9E9E9E))
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                MiniStat("Putts", "${round.totalPutts}")
                MiniStat("FIR", "${round.fairwaysHit}")
                MiniStat("GIR", "${round.greensInRegulation}")
                MiniStat("Holes", "${round.holeScores.size}")
            }
        }
    }
}

@Composable
fun MiniStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
        Text(label, fontSize = 10.sp, color = Color(0xFF9E9E9E))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoundDetailScreen(round: RoundScore, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(round.courseId, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Summary header
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val scoreColor = when {
                        round.relativeToPar < 0 -> GolfGreenLight
                        round.relativeToPar == 0 -> GoldAccent
                        else -> Color(0xFFEF5350)
                    }
                    BigStatSimple("${round.totalStrokes}", "Total", Color.White)
                    BigStatSimple("${if (round.relativeToPar >= 0) "+" else ""}${round.relativeToPar}", "vs Par", scoreColor)
                    BigStatSimple("${round.totalPutts}", "Putts", SkyBlue)
                    BigStatSimple("${round.fairwaysHit}", "FIR", GolfGreenLight)
                    BigStatSimple("${round.greensInRegulation}", "GIR", GolfGreenLight)
                }
            }

            // Hole-by-hole
            if (round.holeScores.isNotEmpty()) {
                Text("HOLE BY HOLE", fontSize = 11.sp, color = Color(0xFF9E9E9E), letterSpacing = 2.sp)

                // Front 9
                ScorecardTable(round.holeScores.take(9), "Front Nine")

                // Back 9
                if (round.holeScores.size > 9) {
                    ScorecardTable(round.holeScores.drop(9), "Back Nine")
                }
            }
        }
    }
}

@Composable
fun BigStatSimple(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Black, color = color)
        Text(label, fontSize = 11.sp, color = Color(0xFF9E9E9E))
    }
}

@Composable
fun ScorecardTable(holes: List<com.golfsim.app.models.HoleScore>, title: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(8.dp))

            // Header
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("Hole", "Par", "Score", "+/-", "Putts").forEachIndexed { i, h ->
                    Text(
                        h, fontSize = 11.sp, color = Color(0xFF9E9E9E),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(if (i == 0) 0.6f else 1f)
                    )
                }
            }
            Divider(color = Color(0xFF2E4A2E), modifier = Modifier.padding(vertical = 4.dp))

            holes.forEach { hole ->
                val relColor = when {
                    hole.relativeToPar < -1 -> Color(0xFF42A5F5)
                    hole.relativeToPar == -1 -> GolfGreenLight
                    hole.relativeToPar == 0 -> Color.White
                    hole.relativeToPar == 1 -> GoldAccent
                    else -> Color(0xFFEF5350)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${hole.holeNumber}", fontSize = 14.sp, color = Color.White,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(0.6f))
                    Text("${hole.par}", fontSize = 14.sp, color = Color(0xFF9E9E9E),
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    Text("${hole.strokes}", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = relColor, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    val relStr = when {
                        hole.relativeToPar < 0 -> "${hole.relativeToPar}"
                        hole.relativeToPar == 0 -> "E"
                        else -> "+${hole.relativeToPar}"
                    }
                    Text(relStr, fontSize = 12.sp, color = relColor,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    Text("${hole.putts}", fontSize = 14.sp, color = Color(0xFF9E9E9E),
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }

            Divider(color = Color(0xFF2E4A2E), modifier = Modifier.padding(vertical = 4.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Total", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(0.6f))
                Text("${holes.sumOf { it.par }}", fontSize = 12.sp, color = Color(0xFF9E9E9E),
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Text("${holes.sumOf { it.strokes }}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                val tot = holes.sumOf { it.relativeToPar }
                val totColor = when {
                    tot < 0 -> GolfGreenLight
                    tot == 0 -> Color.White
                    else -> Color(0xFFEF5350)
                }
                Text("${if (tot >= 0) "+$tot" else "$tot"}", fontSize = 12.sp, color = totColor,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Text("${holes.sumOf { it.putts }}", fontSize = 12.sp, color = Color(0xFF9E9E9E),
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
    }
}
