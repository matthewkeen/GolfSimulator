package com.golfsim.app.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.golfsim.app.ui.screens.*

@Composable
fun GolfSimulatorApp(vm: GolfSimViewModel = viewModel()) {
    val screen by vm.currentScreen.collectAsState()

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            fadeIn() togetherWith fadeOut()
        },
        label = "screen_transition"
    ) { targetScreen ->
        when (targetScreen) {
            Screen.HOME -> HomeScreen(vm)
            Screen.SIMULATOR -> SimulatorScreen(vm)
            Screen.SCORECARD -> ScorecardScreen(vm)
            Screen.STATS -> StatsScreen(vm)
            Screen.SETTINGS -> SettingsScreen(vm)
            Screen.COURSE_SELECT -> CourseSelectScreen(vm)
            Screen.CLUB_SELECT -> ClubSelectScreen(vm)
            Screen.ROUND_SETUP -> RoundSetupScreen(vm)
        }
    }
}
