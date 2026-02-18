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
import com.golfsim.app.ui.*
import com.golfsim.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: GolfSimViewModel) {
    val settings by vm.settings.collectAsState()
    var localSettings by remember { mutableStateOf(settings) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = {
                        vm.updateSettings(localSettings)
                        vm.navigateTo(Screen.HOME)
                    }) {
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Player Settings
            SettingsSection("PLAYER") {
                OutlinedTextField(
                    value = localSettings.playerName,
                    onValueChange = { localSettings = localSettings.copy(playerName = it) },
                    label = { Text("Player Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GolfGreenLight,
                        unfocusedBorderColor = Color(0xFF2E4A2E),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedLabelColor = GolfGreenLight,
                        unfocusedLabelColor = Color(0xFF9E9E9E)
                    )
                )
            }

            // Camera Settings
            SettingsSection("CAMERA & TRACKING") {
                Text("Camera Setup Mode", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                CameraSetupMode.values().forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { localSettings = localSettings.copy(cameraSetupMode = mode) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = localSettings.cameraSetupMode == mode,
                            onClick = { localSettings = localSettings.copy(cameraSetupMode = mode) },
                            colors = RadioButtonDefaults.colors(selectedColor = GolfGreenLight)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(mode.name.replace("_", " "), color = Color.White, fontSize = 14.sp)
                            Text(mode.description, color = Color(0xFF9E9E9E), fontSize = 12.sp)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Text("Tracking Sensitivity", fontSize = 13.sp, color = Color.White)
                Slider(
                    value = localSettings.sensitivity,
                    onValueChange = { localSettings = localSettings.copy(sensitivity = it) },
                    valueRange = 0.5f..2.0f,
                    colors = SliderDefaults.colors(thumbColor = GolfGreenLight, activeTrackColor = GolfGreenLight)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Low", fontSize = 11.sp, color = Color(0xFF9E9E9E))
                    Text("${String.format("%.1f", localSettings.sensitivity)}x", color = GolfGreenLight)
                    Text("High", fontSize = 11.sp, color = Color(0xFF9E9E9E))
                }
            }

            // Display Settings
            SettingsSection("DISPLAY") {
                ToggleSetting("Show Trajectory", "Draw ball flight path on result", localSettings.showTrajectory) {
                    localSettings = localSettings.copy(showTrajectory = it)
                }
                ToggleSetting("Use Metric", "Show distances in meters", localSettings.useMetric) {
                    localSettings = localSettings.copy(useMetric = it)
                }
            }

            // Feedback
            SettingsSection("FEEDBACK") {
                ToggleSetting("Haptic Feedback", "Vibrate on shot detection", localSettings.hapticFeedback) {
                    localSettings = localSettings.copy(hapticFeedback = it)
                }
                ToggleSetting("Sound Effects", "Audio cues for shot events", localSettings.soundEnabled) {
                    localSettings = localSettings.copy(soundEnabled = it)
                }
            }

            // Setup guide
            SettingsSection("CAMERA SETUP GUIDE") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SetupTip("📱", "Mount your Pixel 7 on a tripod or stable surface")
                    SetupTip("📐", "Position camera 8-12 feet from the tee")
                    SetupTip("💡", "Ensure good lighting on the ball — avoid direct backlight")
                    SetupTip("⚪", "Use a white golf ball for best tracking accuracy")
                    SetupTip("🎯", "Side view gives most accurate speed and launch angle data")
                    SetupTip("📊", "Pixel 7 camera uses 60fps for smooth ball detection")
                }
            }

            // Save button
            Button(
                onClick = {
                    vm.updateSettings(localSettings)
                    vm.navigateTo(Screen.HOME)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GolfGreenLight),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Save Settings", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            // Version info
            Text(
                "Golf Simulator v1.0 • Pixel 7 Optimized",
                fontSize = 12.sp,
                color = Color(0xFF9E9E9E),
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontSize = 11.sp, color = Color(0xFF9E9E9E), letterSpacing = 2.sp)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun ToggleSetting(title: String, subtitle: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp)
            Text(subtitle, color = Color(0xFF9E9E9E), fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = GolfGreenLight)
        )
    }
}

@Composable
fun SetupTip(icon: String, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(icon, fontSize = 16.sp)
        Text(text, fontSize = 13.sp, color = Color(0xFFB0BEC5))
    }
}
