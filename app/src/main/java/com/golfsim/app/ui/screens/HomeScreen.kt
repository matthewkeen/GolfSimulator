package com.golfsim.app.ui.screens

import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.golfsim.app.ui.GolfSimViewModel
import com.golfsim.app.ui.Screen
import com.golfsim.app.ui.theme.*

@Composable
fun HomeScreen(vm: GolfSimViewModel) {
    val savedRounds by vm.savedRounds.collectAsState()
    val settings by vm.settings.collectAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "bg")
    val gradientAngle by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing)),
        label = "angle"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(GolfGreenDark, DarkBackground, Color(0xFF0A0F0A))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))

            // App logo / title
            Text(
                text = "⛳",
                fontSize = 72.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "GOLF SIM",
                fontSize = 36.sp,
                fontWeight = FontWeight.Black,
                color = GolfGreenLight,
                letterSpacing = 6.sp
            )
            Text(
                text = "Powered by Pixel 7 Camera",
                fontSize = 13.sp,
                color = GoldAccent,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "Welcome back, ${settings.playerName}",
                fontSize = 14.sp,
                color = Color(0xFFA5D6A7),
                modifier = Modifier.padding(bottom = 32.dp)
            )

            // Quick play button
            Button(
                onClick = { vm.navigateTo(Screen.SIMULATOR) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GolfGreenLight),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(8.dp))
                Text("QUICK PRACTICE", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(16.dp))

            // Main menu grid
            val menuItems = listOf(
                MenuCard("Play Round", "Full 18-hole round", Icons.Default.GolfCourse, GolfGreen, Screen.ROUND_SETUP),
                MenuCard("Select Course", "Choose your course", Icons.Default.Map, SkyBlue, Screen.COURSE_SELECT),
                MenuCard("Statistics", "View your game data", Icons.Default.BarChart, GoldAccent, Screen.STATS),
                MenuCard("Scorecards", "Past rounds", Icons.Default.Score, Color(0xFFAB47BC), Screen.SCORECARD),
            )

            menuItems.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    row.forEach { item ->
                        HomeMenuCard(
                            item = item,
                            modifier = Modifier.weight(1f),
                            onClick = { vm.navigateTo(item.screen) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(8.dp))

            // Recent rounds preview
            if (savedRounds.isNotEmpty()) {
                Text(
                    "Recent Rounds",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
                savedRounds.take(3).forEach { round ->
                    RecentRoundCard(round)
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(8.dp))

            // Settings button
            OutlinedButton(
                onClick = { vm.navigateTo(Screen.SETTINGS) },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Color(0xFF4CAF50))
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Settings", color = GolfGreenLight)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

data class MenuCard(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val color: Color,
    val screen: Screen
)

@Composable
fun HomeMenuCard(item: MenuCard, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier.height(110.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, item.color.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                item.icon,
                contentDescription = null,
                tint = item.color,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(item.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
            Text(item.subtitle, fontSize = 11.sp, color = Color(0xFF9E9E9E))
        }
    }
}

@Composable
fun RecentRoundCard(round: com.golfsim.app.models.RoundScore) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(round.courseId, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                Text(
                    java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).format(java.util.Date(round.date)),
                    color = Color(0xFF9E9E9E), fontSize = 12.sp
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                val scoreColor = when {
                    round.relativeToPar < 0 -> Color(0xFF66BB6A)
                    round.relativeToPar == 0 -> GoldAccent
                    else -> Color(0xFFEF5350)
                }
                Text(
                    "${if (round.relativeToPar >= 0) "+" else ""}${round.relativeToPar}",
                    color = scoreColor,
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp
                )
                Text("${round.totalStrokes} strokes", color = Color(0xFF9E9E9E), fontSize = 12.sp)
            }
        }
    }
}
