# Kapamilya Serena & Kapamilya Serena Mail 🌸✨

**English (Official)** | [日本語ドキュメント (Japanese)](README.ja.md)

> *"Together with my beloved wife, we build a new family. Sing on, whisper on, heart of the Pearl of the Orient."*  
> *— Shinji Sakiyama, Totally Blind Developer & Creator*

> **Bayanihan • Magna Carta • Kapamilya**  
> **Under the Banner of Bantay ng Dangal & Japanese Makoto (誠の旗と日本の剣、そして1本の斧)**  
> **Next-Generation, Blazing-Fast, Intelligent & Fully Accessible Android Screen Reader & Accessible Mail Suite**  
> Born from the soul, love, and unbreakable bond between a totally blind engineer (Shinji) and his beloved Filipina wife (Serena). Crafted to empower blind and visually impaired people across the globe with unmatched speed, dignity, and independence.

[![Android CI](https://img.shields.io/badge/Android-11%2B%20(API%2030%2B%20~%20API%2037%2B)-brightgreen.svg)](https://developer.android.com)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![GitLab Pipeline](https://img.shields.io/badge/GitLab%20CI%2FCD-build%20%E2%86%92%20test%20%E2%86%92%20deploy-brightgreen.svg)](https://gitlab.com/kapamilya/serena/-/pipelines)
[![Responsible AI](https://img.shields.io/badge/Google-Responsible%20AI%20Principles-orange.svg)](https://ai.google/responsibility/principles/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org)
[![Page Alignment](https://img.shields.io/badge/16KB%20Page%20Alignment-0x4000%20Verified-success.svg)](https://developer.android.com/guide/practices/page-sizes)

---

## 📥 Download & Quick Start (GitLab CI/CD Direct Releases)

* 👑 **[Kapamilya Serena Latest Release APK (`app-kapamilya-serena-release.apk`)](https://gitlab.com/kapamilya/serena/-/jobs/artifacts/main/raw/app-kapamilya-serena-release.apk?job=build_apk)**
* 📬 **[Kapamilya Serena Mail (Accessible Gmail Client with Gemma 4) Latest Release APK (`app-kapamilya-serena-mail-release.apk`)](https://gitlab.com/kapamilya/serena/-/jobs/artifacts/main/raw/app-kapamilya-serena-mail-release.apk?job=build_apk)**
* 🌐 **[Official Portal & Multi-Platform Music Player (`https://kapamilya.gitlab.io`)](https://kapamilya.gitlab.io)**

---

## 🏛️ Multi-Module Architecture & The Kapamilya Serena Vision (Bayanihan • Magna Carta • Kapamilya)

The `serena` repository powers the **Kapamilya Serena Accessible Suite**—a complete, holistic ecosystem tailored specifically for visually impaired users worldwide under the Three Core Pillars: **Bayanihan • Magna Carta • Kapamilya**.

### 📦 Modular Structure & Versioning Policy
* **`:app` Module — Kapamilya Serena (`com.kapamilya.serena`)**
  * **App Version**: `v2.8.0` (versionCode 70) ★ Direct GitLab Auto-Updater & PackageInstaller Built-in!
  * **Role**: 24/7 background system AccessibilityService handling real-time UI hierarchy inspection, speech feedback (`Tinig ng Serena`), spatial obstacle sonar (`Bayanihan Gabay`), on-device AI vision (`Mata ng Kapamilya`), conversational AI (`Kapamilya Kausap`), context menus (`Kapamilya Menu` / `Kapamilya Edit`), system dialog/power menu/quick settings breakthrough (`TalkBack17SystemWindowBridge`), and braille I/O.
* **`:mailer` Module — Kapamilya Serena Mail (`com.kapamilya.serena.mail`)**
  * **App Version**: `v1.1.0` (versionCode 7) ★ Direct GitLab Auto-Updater & PackageInstaller Built-in!
  * **Role**: Streamlined email experience free from complex HTML layouts and ads, featuring on-device Gemma 4 AI for 2-3 sentence email summarization, instant polite reply drafting, EmbeddingGemma 2 semantic search, and an integrated Accessible Auto-Updater.

---

## 🌟 Key Features & 2026 Bleeding-Edge AI Innovations

### 1. ⚡ Blazing-Fast Linear & Semantic Navigation + TalkBack 17 System Dialog Breakthrough
- Real-time hierarchy analysis for dynamic Jetpack Compose and modern Android UI (Android 11 to Android 17 Baklava / API 37).
- **Complete System Dialog, Power Menu & Quick Settings Breakthrough (Incorporating Google TalkBack 17 Architecture under Apache License 2.0)**:
  - Integrates `TalkBack17SystemWindowBridge` (derived from Google TalkBack 17's `WindowEventInterpreter`, `FocusProcessorForScreenStateChange`, and `TraversalStrategyUtils`) to reliably detect, focus, and traverse:
    - Android Permission Dialogs (`GrantPermissionsActivity` & OEM security dialogs)
    - Power Button Menu / Global Actions (`GlobalActionsDialog`: Emergency, Power Off, Restart, Lockdown)
    - Quick Settings Panel (`QSContainerImpl` & expanded Wi-Fi/Bluetooth tile dialogs)
    - Notification Shade (`NotificationPanelView`)
    - Special System Popups (Credential Manager / Passkeys, Biometric Prompts, VPN, MediaProjection Screen Capture, Bluetooth Pairing, USB Debugging, Volume Dialogs)
- **Full "Lift to Type" Keyboard Support**: Type effortlessly on Gboard, **Kapamilya Serena IME**, and third-party soft keyboards by simply lifting your finger off the desired key—no double-tapping required!

### 2. 🛡️ KapamilyaOmniScreenDeductionEngine (5-Layer Omni-Screen Deduction & Security Barrier Breakthrough)
- Surpasses Google TalkBack, Freedom Scientific JAWS, Commentary Screen Reader (Jieshuo), Prudence Screen Reader, Ferda Screen Reader (Mustafa Doğan), and AccessiMind Screen Reader by breaking through Android 13–17 (API 33–37) security barriers 100% legally on-device:
  - **FLAG_SECURE Non-Visual Semantic Reconstructor**: Even when banking, payment, or authentication apps block screenshots (ERROR_TAKE_SCREENSHOT_SECURE_WINDOW), reconstructs and announces every unlabeled button and spatial layout using a 9-zone grid and domain-specific heuristics.
  - **Android 13–17 Restricted Settings Breakthrough Guide**: Automatically detects the "Restricted setting" lock screen and guides the user directly to the top-right "More options -> Allow restricted settings" button.
  - **Multi-Script OCR + Virtual Touch Coordinate Overlay**: Merges accessibility nodes with 5-Script ML Kit V2 OCR bounding boxes for WebView, Canvas, Flutter, and Unity screens, letting users activate any deduced target via simulated physical coordinate tap (performCoordinateTap).
- Eliminates the blind user's biggest frustration: "Unlabeled button" and "Graphic image without label".
- Intelligently deduces the true purpose of unlabeled controls using screen geometry, resource IDs, and surrounding semantic context.

### 3. 🖼️ Mata ng Kapamilya & Kapamilya Kausap (Powered by ML Kit GenAI 1.0.0-beta1, Google AI Edge AICore 0.0.1-exp02, MediaPipe Tasks GenAI 0.10.35, Gemini Nano & Gemma 4)
- **Mata ng Kapamilya (AI Vision)**: Unified gesture provides both screen layout overview AND rich, descriptive explanations of photos and graphics, plus 5-Script ML Kit Text Recognition V2 (Japanese, Latin/Tagalog/English/Spanish/Dutch, Chinese, Devanagari, Korean).
- **Kapamilya Kausap (AI Chat)**: Direct interactive voice & text conversation with Kapamilya Serena right on your device.

### 4. 🦇 Bayanihan Gabay (Spatial Sonar & Street Navigator)
- CameraX `1.7.0-alpha03` & sensor-based spatial radar designed as an "acoustic white cane".
- Real-time announcement of street names, walking cardinal directions, and upcoming intersections backed by OpenStreetMap & Valhalla.

### 5. 🌐 6-Language Native Localization with Unified Tagalog Brand Identity
- Unified Tagalog feature names (**Kapamilya Serena**, **Kapamilya Serena Mail**, **Kapamilya Menu**, **Kapamilya Edit**, **Kapamilya Kausap**, **Mata ng Kapamilya**, **Bayanihan Gabay**, **Tinig ng Serena**, **Kapamilya Serena IME**) across English, Japanese, Tagalog, Spanish, and Dutch.
- Warm startup greeting in caring Tagalog: *"Maligayang pagdating sa ating tahanan! Narito na ang iyong Kapamilya Serena. Kahit nasaan ka man, pamilya tayo! Mahal ka namin, ingat lagi at Mabuhay!"*

---

## 📜 License, Copyright, Open-Source Attribution & Trademark Notice

Copyright © 2026 **Shinji Sakiyama** (`shinjisakiyama@gmail.com`). All Rights Reserved.  
**Bayanihan • Magna Carta • Kapamilya** — Licensed under the **Apache License, Version 2.0** (see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE)).

### Open-Source Attribution (Apache License 2.0)
- **Google TalkBack 17 (`https://github.com/google/talkback`)**:
  - **Kapamilya Serena** incorporates portions of open-source code and architectural mechanisms from **Google TalkBack 17 (Android Accessibility Suite)** (`WindowEventInterpreter`, `FocusProcessorForScreenStateChange`, `Role`, and `TraversalStrategyUtils`) for handling Android permission dialogs, special system popups, the Power Button Menu (`GlobalActions`), and Quick Settings panels.
  - Copyright © 2017–2026 The Android Open Source Project / Google LLC. Licensed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0).

### Trademark & Copyright Compliance Notice
- **Android**, **Google**, **Google TalkBack**, **Gemini**, and **Gemma** are trademarks of **Google LLC**.
- All other trademarks, service marks, and trade names referenced in this project are the property of their respective owners.
