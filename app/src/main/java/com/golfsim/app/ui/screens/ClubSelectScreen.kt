package com.golfsim.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.golfsim.app.models.ClubType
import com.golfsim.app.ui.GolfSimViewModel
import com.golfsim.app.ui.Screen
import com.golfsim.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClubSelectScreen(vm: GolfSimViewModel) {
    val selectedClub by vm.selectedClub.collectAsState()

    val categories = listOf(
        "Woods" to ClubType.values().filter { it.name.contains("WOOD") || it == ClubType.DRIVER },
        "Hybrids" to listOf(ClubType.HYBRID),
        "Irons" to ClubType.values().filter { it.name.startsWith("IRON") },
        "Wedges" to ClubType.values().filter { it.name.contains("WEDGE") },
        "Putter" to listOf(ClubType.PUTTER)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select Club", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { vm.navigateTo(Screen.SIMULATOR) }) {
                        Icon(Icons.Default.ArrowBack, null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = DarkBackground
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            categories.forEach { (category, clubs) ->
                item {
                    Text(
                        category.uppercase(),
                        fontSize = 11.sp,
                        color = Color(0xFF9E9E9E),
                        letterSpacing = 2.sp,
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                    )
                }
                items(clubs) { club ->
                    ClubRow(
                        club = club,
                        isSelected = club == selectedClub,
                        onClick = {
                            vm.selectClub(club)
                            vm.navigateTo(Screen.SIMULATOR)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ClubRow(club: ClubType, isSelected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) GolfGreenDark else DarkSurface
        ),
        border = if (isSelected) BorderStroke(1.dp, GolfGreenLight) else null,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(club.icon, fontSize = 28.sp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(club.displayName, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                    Text(
                        "Loft: ${club.loftDegrees}°  •  Avg carry: ${club.avgCarryYards.toInt()} yds",
                        fontSize = 12.sp, color = Color(0xFF9E9E9E)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("${club.maxSpeedMph.toInt()} mph", fontWeight = FontWeight.Bold, color = GoldAccent, fontSize = 14.sp)
                    Text("max speed", fontSize = 10.sp, color = Color(0xFF9E9E9E))
                }
                if (isSelected) Icon(Icons.Default.Check, null, tint = GolfGreenLight)
            }
        }
    }
}
