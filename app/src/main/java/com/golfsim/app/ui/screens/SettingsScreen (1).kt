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

            // ─── Camera Calibration ─────────────────────────────────────────────
            SettingsSection("CAMERA CALIBRATION") {
                Text(
                    "Tune ball detection for your exact lighting, ball size, and camera distance. " +
                    "Run calibration whenever you change your setup.",
                    fontSize = 13.sp,
                    color = Color(0xFFB0BEC5),
                    lineHeight = 19.sp
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.navigateTo(Screen.CALIBRATION) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GolfGreenLight),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Open Calibration Wizard",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = GoldAccent, modifier = Modifier.size(14.dp))
                    Text(
                        "Calibration takes ~60 seconds and greatly improves accuracy",
                        fontSize = 11.sp,
                        color = GoldAccent
                    )
                }
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

                Text(
                    "Tracking Sensitivity",
                    fontSize = 13.sp,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Low", fontSize = 12.sp, color = Color(0xFF9E9E9E))
                    Slider(
                        value = localSettings.sensitivity,
                        onValueChange = { localSettings = localSettings.copy(sensitivity = it) },
                        valueRange = 0.5f..2.0f,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(thumbColor = GolfGreenLight, activeTrackColor = GolfGreenLight)
                    )
                    Text("High", fontSize = 12.sp, color = Color(0xFF9E9E9E))
                }
            }

            // App Settings
            SettingsSection("APP") {
                ToggleSetting(
                    "Show Trajectory",
                    "Draw the ball's flight path arc on screen",
                    localSettings.showTrajectory
                ) { localSettings = localSettings.copy(showTrajectory = it) }
                Divider(color = Color(0xFF1E3A1E), modifier = Modifier.padding(vertical = 4.dp))
                ToggleSetting(
                    "Haptic Feedback",
                    "Vibrate on shot detection",
                    localSettings.hapticFeedback
                ) { localSettings = localSettings.copy(hapticFeedback = it) }
                Divider(color = Color(0xFF1E3A1E), modifier = Modifier.padding(vertical = 4.dp))
                ToggleSetting(
                    "Sound Effects",
                    "Play audio on swing and ball detection",
                    localSettings.soundEnabled
                ) { localSettings = localSettings.copy(soundEnabled = it) }
                Divider(color = Color(0xFF1E3A1E), modifier = Modifier.padding(vertical = 4.dp))
                ToggleSetting(
                    "Metric Units",
                    "Show distances in metres instead of yards",
                    localSettings.useMetric
                ) { localSettings = localSettings.copy(useMetric = it) }
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
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
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = GolfGreenLight
            )
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
