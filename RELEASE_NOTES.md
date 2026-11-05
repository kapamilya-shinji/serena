# 🚀 Kapamilya Serena v2.8.2 & Kapamilya Serena Mail v1.1.2 — Official Release Notes

**Bayanihan • Magna Carta • Kapamilya**  
*Under the Banner of Bantay ng Dangal, the Sword of Japanese Makoto (誠), and One Axe*

---

## 🌟 Highlights of v2.8.2 (`Kapamilya Serena` v2.8.2 / `Kapamilya Serena Mail` v1.1.2)

### 1. 🇵🇭🇯🇵 Complete Rebranding to **Kapamilya Serena** & **Kapamilya Serena Mail**
- **Screen Reader (`:app`)**:
  - **App Name**: **`Kapamilya Serena`**
  - **Package ID (`applicationId`)**: **`com.kapamilya.serena`** (`versionCode = 70`, `versionName = "2.8.0"`)
- **Accessible Gmail Client (`:mailer`)**:
  - **App Name**: **`Kapamilya Serena Mail`**
  - **Package ID (`applicationId`)**: **`com.kapamilya.serena.mail`** (`versionCode = 7`, `versionName = "1.1.0"`)
- **Three Core Pillars**: **`Bayanihan • Magna Carta • Kapamilya`** (バヤニハン・マグナカルタ・カパミリャ) — honoring the spirit of mutual aid, human rights under the Magna Carta for Persons with Disabilities and Bantay ng Dangal, and family solidarity.

### 2. 🏷️ Unified Tagalog Feature & Menu Names Across All 6 Supported Languages
- Unified core feature and menu names to authentic Tagalog spellings while preserving each user's native language across all **6 locales** (**English Default**, **English `en`**, **Japanese `ja`**, **Tagalog `tl`**, **Spanish `es`**, and **Dutch `nl`**):
  - **`Kapamilya Menu`**: Global Context Menu (L-gesture: Swipe Down then Right)
  - **`Kapamilya Edit`**: Text Editing Menu
  - **`Kapamilya Kausap`**: On-Device Conversational AI Chat (Gemini Nano / Gemma 4)
  - **`Mata ng Kapamilya`**: AI Vision, Scene Description & 5-Script OCR
  - **`Bayanihan Gabay`**: Spatial Obstacle Sonar & Street/Intersection Navigator
  - **`Tinig ng Serena`**: Multilingual TTS & Voice Engine
  - **`Kapamilya Serena IME`**: Multilingual Phonetic & Lift-to-Type Keyboard

### 3. 🔄 Direct Repository Auto-Updater & Automatic PackageInstaller Resume
- Both **Kapamilya Serena** and **Kapamilya Serena Mail** announce updates clearly in all 6 languages as **"Kapamilya Serena update available"** / **"Kapamilya Serena のアップデートが利用可能です"** and **"Kapamilya Serena Mail update available"** / **"Kapamilya Serena Mail のアップデートが利用可能です"**.
- Downloads `app-kapamilya-serena-release.apk` and `app-kapamilya-serena-mail-release.apk` directly from the official GitHub Releases repository (`shinji5683/cocoa`) and immediately launches the Android `PackageInstaller`.
- If "Install unknown apps" permission is not yet granted, the updater automatically opens the system permission dialog (`ACTION_MANAGE_UNKNOWN_APP_SOURCES`), stores the downloaded APK path, and **automatically launches the PackageInstaller the instant the user returns to the app**.

### 4. ⚡ 2026 Bleeding-Edge Android 17 (API 37), AGP 9.0.0, ML Kit GenAI & On-Device AI Stack
- **Android 17 (`Baklava` / API 37)**: `compileSdk = 37`, `targetSdk = 37`
- **Build Toolchain**: Android Gradle Plugin **`9.0.0`**, Gradle **`9.1.0`**
- **ML Kit GenAI (`1.0.0-beta1`)**: Summarization, Proofreading, Rewriting, Image Description, Speech Recognition, and Prompt APIs
- **Google AI Edge AICore (`0.0.1-exp02`)** & **MediaPipe Tasks GenAI (`0.10.35`)**: On-device Gemini Nano & Gemma 4 inference
- **ML Kit Text Recognition V2 (5 Scripts)** & **CameraX `1.7.0-alpha03`**

---

## 📥 Direct APK Downloads
- **Kapamilya Serena (`com.kapamilya.serena`)**:
  - `app-kapamilya-serena-release.apk` (also mirrored as `app-serena-release.apk`)
- **Kapamilya Serena Mail (`com.kapamilya.serena.mail`)**:
  - `app-kapamilya-serena-mail-release.apk` (also mirrored as `app-serena-mail-release.apk`)

---
*Copyright © 2026 Shinji Sakiyama (`shinjisakiyama@gmail.com`). All Rights Reserved. Bayanihan • Magna Carta • Kapamilya.*
