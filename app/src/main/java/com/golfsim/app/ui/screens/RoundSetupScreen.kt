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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.golfsim.app.models.TeeBox
import com.golfsim.app.ui.GolfSimViewModel
import com.golfsim.app.ui.Screen
import com.golfsim.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoundSetupScreen(vm: GolfSimViewModel) {
    val settings by vm.settings.collectAsState()
    val selectedCourse by vm.selectedCourse.collectAsState()

    var playerName by remember { mutableStateOf(settings.playerName) }
    var selectedTee by remember { mutableStateOf(settings.defaultTeeBox) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Round", fontWeight = FontWeight.Bold) },
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
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Course summary
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("COURSE", fontSize = 11.sp, color = Color(0xFF9E9E9E), letterSpacing = 2.sp)
                    Text(selectedCourse.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(selectedCourse.location, fontSize = 13.sp, color = Color(0xFF9E9E9E))
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Par ${selectedCourse.par}", color = GolfGreenLight, fontWeight = FontWeight.Bold)
                        Text("${selectedCourse.totalYardage} yds", color = Color(0xFF9E9E9E))
                        Text("Rating ${selectedCourse.rating}", color = Color(0xFF9E9E9E))
                    }
                }
            }

            // Player name
            Column {
                Text("PLAYER NAME", fontSize = 11.sp, color = Color(0xFF9E9E9E), letterSpacing = 2.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = playerName,
                    onValueChange = { playerName = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Enter your name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GolfGreenLight,
                        unfocusedBorderColor = Color(0xFF2E4A2E),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            }

            // Tee selection
            Column {
                Text("TEE BOX", fontSize = 11.sp, color = Color(0xFF9E9E9E), letterSpacing = 2.sp)
                Spacer(Modifier.height(8.dp))
                TeeBox.values().forEach { tee ->
                    val yards = selectedCourse.holes.sumOf { it.yardageTees[tee] ?: 0 }
                    if (yards > 0) {
                        TeeSelectionRow(
                            teeBox = tee,
                            yards = yards,
                            isSelected = tee == selectedTee,
                            onClick = { selectedTee = tee }
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { vm.startRound(playerName.ifEmpty { "Golfer" }, selectedTee) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GolfGreenLight),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text("Start Round", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun TeeSelectionRow(teeBox: TeeBox, yards: Int, isSelected: Boolean, onClick: () -> Unit) {
    val teeColor = when (teeBox) {
        TeeBox.BLACK -> Color(0xFF212121)
        TeeBox.BLUE -> Color(0xFF1565C0)
        TeeBox.WHITE -> Color(0xFFF5F5F5)
        TeeBox.GOLD -> GoldAccent
        TeeBox.RED -> Color(0xFFC62828)
    }
    val textColor = if (teeBox == TeeBox.WHITE) Color.Black else Color.White

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) DarkSurfaceVariant else DarkSurface
        ),
        border = if (isSelected) BorderStroke(2.dp, GolfGreenLight) else null,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(teeColor, RoundedCornerShape(4.dp))
                        .border(1.dp, Color.White.copy(0.3f), RoundedCornerShape(4.dp))
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("${teeBox.color} Tees", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                    Text(teeBox.handicap, fontSize = 12.sp, color = Color(0xFF9E9E9E))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("$yards yds", color = Color.White, fontWeight = FontWeight.Bold)
                if (isSelected) Icon(Icons.Default.Check, null, tint = GolfGreenLight, modifier = Modifier.size(20.dp))
            }
        }
    }
}
