# ⛳ Golf Simulator — Pixel 7 Android App

A full-featured Android golf simulator that uses your Google Pixel 7's camera to track ball flight and simulate realistic shot physics.

---

## 📱 App Features

### Core
- **Real ball tracking** via Pixel 7 camera using brightness/blob detection
- **Full 6DOF physics engine** with aerodynamics, Magnus effect, and spin modeling
- **All 14 club types** — from Driver to Lob Wedge with accurate specs
- **3 world-famous courses** — Pebble Beach, St Andrews, Augusta National + Practice Range
- **Shot shape detection** — Straight, Draw, Fade, Hook, Slice, Push, Pull and more

### Shot Analysis
- Ball speed, club head speed, smash factor
- Launch angle, spin rate (backspin + sidespin)
- Swing path and face angle (D-Plane model)
- Carry distance, total distance, offline in feet
- Max height, time of flight
- Landing zone classification (Fairway, Rough, Bunker, Water, OOB, Green)

### Game Modes
- **Quick Practice** — hit shots instantly with any club
- **Full Round** — 18-hole scorecard tracking with GIR, FIR, putts
- **Driving Range** — open ended practice without scoring

### Stats & History
- Session stats (avg carry, avg total, longest drive, offline, fairway %)
- Shot shape breakdown with visual bar chart
- All-time stats across saved rounds
- Full hole-by-hole scorecard with front/back nine

---

## 🔧 Setup Instructions

### Requirements
- Android Studio Hedgehog or newer
- Android SDK 34
- Kotlin 1.9.10
- Google Pixel 7 (Android 13+)

### Build & Install
```bash
git clone <repo>
cd GolfSimulator
# Open in Android Studio, sync Gradle, run on Pixel 7
```

Or build from CLI:
```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 📷 Camera Setup (VERY IMPORTANT for accuracy)

The app uses your Pixel 7's rear camera to detect and track the golf ball. Follow these tips for best results:

| Factor | Recommendation |
|--------|---------------|
| **Distance** | 8–12 feet from tee |
| **Position** | Side-on to golfer (90° perpendicular) |
| **Height** | Camera at waist/ball height |
| **Lighting** | Good ambient light; avoid direct backlight |
| **Ball** | White golf ball (highest detection rate) |
| **Background** | Green mat or dark background preferred |
| **Stability** | Use tripod or phone holder — no hand-holding |

### How Tracking Works
1. Camera scans for a bright circular object (white ball) in the frame
2. When detected, a green targeting ring appears on screen
3. Hit "SHOOT" button then swing — the app captures ball motion frames
4. Ball velocity vector is calculated from position changes
5. This feeds into the physics engine to simulate full flight

### Simulate Mode
If lighting conditions aren't ideal, tap **"Simulate"** instead — this generates statistically realistic shot data for your selected club without camera tracking.

---

## 🏌️ Physics Engine Details

### Ball Flight Model
Uses **Runge-Kutta 4** numerical integration with:
- **Drag**: `Cd = 0.23` (dimpled ball aerodynamics)
- **Magnus Lift**: Spin-dependent lift using `Cl = 0.54 * tanh(spin_factor)`
- **Sidespin**: Lateral Magnus force from face/path differential
- **Spin Decay**: 2% per second decay during flight

### D-Plane Model
Shot shape is determined by:
- `Face Angle` (open/closed relative to target)
- `Swing Path` (in-to-out or out-to-in)
- Face angle accounts for ~75% of starting direction
- Difference between face and path creates curvature

### Smash Factor
| Club | Typical Smash |
|------|----------|
| Driver | 1.48 |
| Woods | 1.44 |
| Mid-irons | 1.38 |
| Wedges | 1.25 |

---

## 📁 Project Structure

```
app/
├── camera/
│   └── GolfCameraManager.kt     # CameraX + ball detection
├── game/
│   └── BallPhysicsEngine.kt     # Full flight physics simulation
├── models/
│   └── GolfModels.kt            # Clubs, courses, shot data
└── ui/
    ├── GolfSimViewModel.kt       # State management
    ├── GolfSimulatorApp.kt       # Navigation
    ├── theme/
    │   └── GolfTheme.kt         # Dark golf-themed colors
    └── screens/
        ├── HomeScreen.kt         # Main menu
        ├── SimulatorScreen.kt    # Camera + shooting UI
        ├── ClubSelectScreen.kt   # 14 clubs with specs
        ├── CourseSelectScreen.kt # 4 courses
        ├── RoundSetupScreen.kt   # Tee/player setup
        ├── ScorecardScreen.kt    # Hole-by-hole scores
        ├── StatsScreen.kt        # Analytics dashboard
        └── SettingsScreen.kt     # App configuration
```

---

## 🚀 Future Enhancements

- [ ] GPU-accelerated ball tracking using ML Kit
- [ ] Wind and elevation simulation
- [ ] Multiplayer scoring support
- [ ] 3D course fly-through view
- [ ] Shot replay with trajectory animation
- [ ] Handicap calculator
- [ ] Integration with Garmin/Apple Watch for heart rate

---

## ⚠️ Accuracy Notes

Camera-based ball tracking has limitations vs commercial systems (TrackMan, Foresight):
- Works best with controlled lighting and white ball
- Speed estimation is approximate (±5-10%)
- Launch angle estimation from 2D camera has inherent limitations
- Use "Simulate" mode for guaranteed realistic output during bad lighting

For best results, use in a dedicated net or garage setup with consistent lighting.

---

MIT License • Built for Pixel 7
