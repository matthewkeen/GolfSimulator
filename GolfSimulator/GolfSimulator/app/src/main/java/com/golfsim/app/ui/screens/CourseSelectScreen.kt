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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.golfsim.app.models.CourseDatabase
import com.golfsim.app.models.Course
import com.golfsim.app.ui.GolfSimViewModel
import com.golfsim.app.ui.Screen
import com.golfsim.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseSelectScreen(vm: GolfSimViewModel) {
    val selectedCourse by vm.selectedCourse.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select Course", fontWeight = FontWeight.Bold) },
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Choose your course to play. Each course has authentic hole layouts and difficulty.",
                    fontSize = 14.sp,
                    color = Color(0xFF9E9E9E),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            items(CourseDatabase.ALL_COURSES) { course ->
                CourseCard(
                    course = course,
                    isSelected = course.name == selectedCourse.name,
                    onClick = {
                        vm.selectCourse(course)
                        vm.navigateTo(Screen.HOME)
                    }
                )
            }
        }
    }
}

@Composable
fun CourseCard(course: Course, isSelected: Boolean, onClick: () -> Unit) {
    val courseEmoji = when {
        course.name.contains("Pebble") -> "🌊"
        course.name.contains("Andrews") -> "🏴󠁧󠁢󠁳󠁣󠁴󠁿"
        course.name.contains("Augusta") -> "🌸"
        else -> "🏌️"
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) GolfGreenDark else DarkSurface
        ),
        border = if (isSelected) BorderStroke(2.dp, GolfGreenLight) else BorderStroke(1.dp, Color(0xFF2E4A2E)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(courseEmoji, fontSize = 36.sp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(course.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        Text(course.location, fontSize = 13.sp, color = Color(0xFF9E9E9E))
                    }
                }
                if (isSelected) {
                    Icon(Icons.Default.CheckCircle, null, tint = GolfGreenLight, modifier = Modifier.size(24.dp))
                }
            }

            Spacer(Modifier.height(12.dp))
            Divider(color = Color(0xFF2E4A2E))
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatBadge("Par", "${course.par}", GolfGreenLight)
                StatBadge("Yards", "${course.totalYardage}", Color.White)
                StatBadge("Rating", "${course.rating}", GoldAccent)
                StatBadge("Slope", "${course.slope}", Color(0xFFEF5350))
            }

            if (course.name.contains("Range")) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "✅ Perfect for warm-up and practice",
                    fontSize = 12.sp,
                    color = GolfGreenLight
                )
            }
        }
    }
}

@Composable
fun StatBadge(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, color = color, fontSize = 18.sp)
        Text(label, fontSize = 11.sp, color = Color(0xFF9E9E9E))
    }
}
