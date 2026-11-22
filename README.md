# VisionAssist: AI-Powered Visual Assistance System

**VisionAssist** is an advanced, voice-first Android application designed specifically for blind and visually impaired individuals. It functions as an ambient visual assistant that eliminates screen dependency: users do not need to look at the screen, manually align a camera viewfinder, or tap shutter buttons. 

By simply saying **"Hey VisionAssist"**, the application automatically captures a fresh image from the rear camera, analyzes the surroundings using Google Gemini Multimodal Vision and on-device Google ML Kit, determines approximate horizontal positions (Left, Center, Right), and speaks a natural, concise description aloud via Text-to-Speech (TTS).

---

## 🌟 Primary Features

1. **Automatic Voice-to-Camera-to-AI Workflow**:
   - Primary wake phrase: **"Hey VisionAssist"** powered by on-device **Vosk** speech recognition.
   - **Zero-Touch Execution**: The user speaks the wake phrase; VisionAssist acknowledges with *"I'm looking around now"*, immediately captures a fresh CameraX frame, analyzes it, and speaks the description aloud.
   - **Continuous Listening**: Automatically resumes listening for follow-up questions once speech completes.

2. **Semantic Scene & Object Understanding**:
   - Integrated with **Google Gemini Multimodal Vision API** (`gemini-2.5-flash`, `gemini-1.5-flash`, `gemini-2.0-flash`).
   - Identifies specific objects by name, environmental context, and approximate horizontal positions (*left*, *center*, *right*).
   - Generates structured, natural speech without robotic asterisks, bullet points, or markdown formatting.

3. **Targeted Object Search & Enumeration**:
   - **Object Search**: *"Where is my bottle?"* or *"Find my phone"* &rarr; Locates the specific item and announces its horizontal position (*"Your water bottle is on the left"*).
   - **Object Counting**: *"How many chairs are there?"* or *"Count the people"* &rarr; Counts the entities and specifies their locations.

4. **On-Device Optical Character Recognition (OCR)**:
   - Powered by **Google ML Kit Text Recognition** (`com.google.mlkit:text-recognition:16.0.1`).
   - Operates completely offline for printed documents, medicine labels, and signboards.
   - Sorts text blocks in natural reading order (top-to-bottom, left-to-right), removes redundant lines, and chunks long documents for speech synthesis.

5. **Honest On-Device Offline Fallback**:
   - When offline or if API quotas are reached, VisionAssist seamlessly falls back to on-device **Google ML Kit Object Detection** (`com.google.mlkit:object-detection:17.0.2`) and ML Kit OCR.
   - The assistant transparently notifies the user: *"Offline mode: I detect a household item on the left..."* without fabricating unavailable cloud outputs.

6. **Android 14+ Compliant Foreground Service**:
   - Managed by `AssistantForegroundService` with `FOREGROUND_SERVICE_MICROPHONE`.
   - Displays a persistent status notification with a one-tap *"Stop Assistant"* action.
   - Uses safe partial wake-locks (`PARTIAL_WAKE_LOCK`) with a 10-minute timeout ceiling to conserve battery.

7. **Accessible Material 3 Interface**:
   - Designed for TalkBack screen reader support with explicit accessibility labels on all controls.
   - High-contrast visual hierarchy (pulsing color-coded status indicator: green for listening, amber for capturing, cyan for AI analyzing, purple for speaking).
   - Large touch targets (minimum 50–58dp height).

---

## 🗣️ Voice Commands Reference

| Category | Voice Command Examples | Assistant Action |
| :--- | :--- | :--- |
| **Wake Word** | *"Hey VisionAssist"*, *"Hey Vision Assist"* | Acknowledges, captures fresh camera image, describes scene & objects |
| **Scene Description** | *"Describe my surroundings"*, *"What is in front of me?"*, *"Describe the room"* | Captures camera image and speaks complete scene description |
| **Object Search** | *"Where is my bottle?"*, *"Find my phone"*, *"Where is the laptop?"* | Identifies target item and states horizontal position (left, center, right) |
| **Object Counting** | *"How many chairs are there?"*, *"Count the people"*, *"How many bottles?"* | Counts target items and announces count and locations |
| **Text Reading / OCR** | *"Read the text"*, *"Read this document"*, *"Read the sign"*, *"What is written?"* | Performs local ML Kit OCR and reads visible text in logical reading order |
| **Follow-Up Questions** | *"What is on the table?"*, *"What color is the mug?"* | Analyzes recent image or captures fresh frame to answer visual question |
| **Speech Controls** | *"Repeat"*, *"Say that again"*, *"What did you say?"* | Repeats the last spoken observation |
| **Speech Controls** | *"Stop"*, *"Stop speaking"*, *"Be quiet"*, *"Pause"* | Immediately stops Text-to-Speech output |
| **Continuous Listening** | *"Start listening"*, *"Stop listening"* | Toggles continuous background listening mode |
| **Guidance** | *"Help"*, *"What can you do?"*, *"Commands"* | Speaks accessible help guide with sample voice commands |

---

## 🏗️ Project Architecture & Package Structure

```
com.hitha.visionassist/
├── camera/
│   └── CameraManager.kt                # CameraX lifecycle, rear camera binding, auto-capture
├── vision/
│   ├── GeminiVisionClient.kt           # Google Gemini Multimodal REST API integration
│   ├── ObjectDetectionEngine.kt        # On-device ML Kit object detection & spatial position logic
│   ├── OCRManager.kt                   # On-device ML Kit OCR, reading order sort & text chunking
│   ├── ImageProcessor.kt               # Safe downsampling, EXIF orientation & cache cleanup
│   └── VisionEngine.kt                 # High-level vision orchestration & offline fallback
├── speech/
│   ├── WakeWordDetector.kt             # Offline Vosk acoustic model listener & debouncing
│   ├── VoiceCommandParser.kt           # Pure natural language voice command parsing
│   ├── TextToSpeechManager.kt          # Android TTS wrapper, queue management & repeat/stop
│   └── SpeechRecognitionManager.kt     # Coordinates mic listening and TTS lifecycle
├── service/
│   └── AssistantForegroundService.kt   # Android 14+ foreground service with ongoing notification
├── data/
│   ├── model/
│   │   └── VisionModels.kt             # Typed data classes (AssistantState, HorizontalPosition, etc.)
│   └── repository/
│       └── VisionRepository.kt         # Clean data layer contract decoupling UI from ML engines
├── ui/
│   ├── components/
│   │   └── VoiceAssistantHeroCard.kt   # Pulsing accessible state card with high-contrast indicator
│   ├── theme/
│   │   ├── Color.kt                    # High-contrast accessible color palette
│   │   ├── Theme.kt                    # Material 3 theme definition
│   │   └── Type.kt                     # Accessible typography styles
│   └── VisionAssistScreen.kt           # Jetpack Compose UI with TalkBack semantics
└── MainActivity.kt                     # Runtime permissions, camera integration, compose content
```

---

## 🛠️ Technology Stack

- **Platform**: Native Android (Min SDK: 26 / Android 8.0, Target SDK: 35, Compile SDK: 36)
- **Language**: Kotlin 2.2.10 (JVM 11 target)
- **UI Framework**: Jetpack Compose with Material 3 (BOM 2025.08.01)
- **Camera API**: Android CameraX 1.4.2 (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`)
- **Offline Wake-Word Detection**: Vosk Android 0.3.47 with bundled offline acoustic model in assets
- **Speech Synthesis**: Android Native `TextToSpeech` with `UtteranceProgressListener`
- **On-Device Vision**:
  - Google ML Kit Object Detection 17.0.2 (`com.google.mlkit:object-detection`)
  - Google ML Kit Text Recognition 16.0.1 (`com.google.mlkit:text-recognition`)
- **Cloud Foundation Model**: Google Gemini Multimodal Vision API via OkHttp 4.12.0
- **Asynchronous Architecture**: Kotlin Coroutines & Lifecycle CoroutineScope

---

## ⚙️ Configuration & API Key Setup

### 1. Configure `local.properties`
In the project root folder (`C:\Users\hitha\AndroidStudioProjects\VisionAssist\local.properties`), configure your Google Gemini API key:

```properties
sdk.dir=C\:\\Users\\hitha\\AppData\\Local\\Android\\Sdk
GEMINI_API_KEY=YOUR_GEMINI_API_KEY
```

> [!IMPORTANT]
> The API key is securely injected into `BuildConfig.GEMINI_API_KEY` at build time. It is never hardcoded in source files or checked into version control. If no API key is provided, the application automatically operates in local offline mode using on-device ML Kit detection and OCR.

---

## 🧪 Automated Testing & Verification

VisionAssist includes a unit testing suite verifying all voice parsing variations, spatial horizontal classifications, OCR deduplication, chunking, and Gemini JSON handling:

```powershell
.\gradlew.bat testDebugUnitTest
```

### Test Results
```
19 tests completed, 0 failed (100% Pass Rate)
- VoiceCommandParser: Wake-word detection variations, attached commands, scene parsing
- Object Search & Counting: Extraction of query targets ("bottle", "phone", "people", "chairs")
- Spatial Position: Left (<0.35), Center (0.35–0.65), Right (>0.65) boundary classifications
- OCR Processing: Line deduplication, 50-word speech chunking, empty result handling
- Gemini Client: Structured JSON parsing, markdown stripping, safety block handling
```

---

## 📦 Building the Production Debug APK

To compile and assemble the final APK:

```powershell
.\gradlew.bat clean assembleDebug
```

### Output APK Location:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Installation via ADB:
```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## ⚠️ Known Limitations & Android Security Constraints

1. **Monocular 2D Depth Limitations**: Monocular phone cameras cannot determine physical distances in feet or meters. VisionAssist classifies relative horizontal positions (Left, Center, Right) and relative bounding box size (Foreground vs. Background), without claiming collision prevention.
2. **Android 14+ Background Camera Privacy**: Android OS strictly disallows camera access without an active foreground window. VisionAssist maintains `FLAG_KEEP_SCREEN_ON` during active visual assistance to ensure reliable automatic image capture.
3. **Network Latency**: While on-device ML Kit detection operates in under 150ms, cloud Gemini visual analysis requires an active Internet connection (Wi-Fi / 4G / 5G) with typical response latency of 1.5–2.5 seconds.
