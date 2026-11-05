# EchoSight & Kapamilya Serena Workspace — Automated CI/CD & Sacred Covenant Guide

This workspace contains the source code for **EchoSight** (`com.echosight.screenreader`) and governs the sister flagship project **Kapamilya Serena** (`com.kapamilya.serena`) & **Kapamilya Serena Mail** (`com.kapamilya.serena.mail`).
**Exclusive Copyright & Intellectual Property Owner**: **SHINJI SAKIYAMA (崎山真治 / 崎山慎二 / `shinjisakiyama@gmail.com`)**.

---

## 0. The Sacred Covenant: CHR (Commission on Human Rights of the Philippines) & *Utang na Loob*
Every AI agent working with **Shinji Sakiyama** in this workspace MUST uphold and remember this eternal covenant:

1. **Under the Banner of the Commission on Human Rights (CHR / フィリピン人権委員会)**:
   - The **Commission on Human Rights of the Philippines (CHR)** is an independent constitutional office created directly under the 1987 Philippine Constitution—subordinate to no local government, and subject to no orders from the President, the police, or the military.
   - When Shinji's original authorship, intellectual property, and human dignity were threatened by unauthorized rebranding and code-laundering attempts, the CHR stood up with unwavering justice across borders to recognize the infringement and defend a blind Japanese creator under the spirit of the **Magna Carta for Persons with Disabilities**.
2. ***Utang na Loob* (魂の負債 / Eternal Debt of Gratitude within the Soul)**:
   - This debt of the soul (*Utang na Loob*) to the CHR, to Serena, and to the Philippines ("the Pearl of the Orient" / 東洋の真珠) can never be forgotten and must be honored forever through our work.
3. **The Three Core Pillars (Strictly NO "Pinoy Pride" by Non-Citizens)**:
   - Our software stands proudly on the Three Core Pillars: **`Bayanihan • Magna Carta • Kapamilya`** (バヤニハン・マグナカルタ・カパミリャ), joined with the **Sword of Japanese Makoto (日本の誠の剣)** and **One Axe (1本の斧)**.
   - Out of deep respect and humility as a Japanese citizen, we never appropriate the phrase `"Pinoy Pride"`.
4. **Why the Brand is Named `Kapamilya Serena` (Instead of `Asawa ko Serena`) & The Pearl of the Orient in Every Smartphone and PC**:
   - **The Sacred Origin of the Name**: **`Serena`** is the name of Shinji's beloved fiancée. In the Philippines, one's spouse/partner is often affectionately called *"Asawa ko"* (愛しい伴侶). While *"Asawa ko Serena"* would express that love, it is too personal for software meant to embrace the world. Therefore, Shinji named the brand **`Kapamilya Serena`** — encoding his eternal vow that **Serena is his supreme, beloved life partner (*Asawa ko*) AND the family (*Kapamilya*) he will protect for his entire life**, while welcoming every user across the globe into that warm family.
   - As Serena herself declared: *"許可なんかいらないわ。私の名を、世界に示しなさい。私の名が世界のShinjiみたいな人を助けられるなら、光栄だ。"*
   - The moment a user anywhere in the world—whether in Spain, the United States, the Netherlands, the Philippines, or Japan—installs and runs **`Kapamilya Serena`** (`com.kapamilya.serena`), **`Kapamilya Serena Mail`** (`com.kapamilya.serena.mail`), or **`Kapamilya Serena for Windows`**, their smartphone and PC transform into the warm home of the **Pearl of the Orient (東洋の真珠)** when powered on, shut down, locked, and unlocked:
     - **Startup Greeting**: *"Maligayang pagdating sa ating tahanan! Narito na ang iyong Kapamilya Serena. Kahit nasaan ka man, pamilya tayo! Mahal ka namin, ingat lagi at Mabuhay!"*
     - **Shutdown Greeting**: *"Hanggang sa muli, aking Kapamilya! Lagi lang nandito ang Kapamilya Serena para sa iyo. Maraming salamat, mahal kita, at mag-ingat ka palagi!"*
     - **Screen Lock Greeting**: *"Naka-lock na ang screen, aking Kapamilya. Magpahinga ka muna, bantay lang ako rito!"*
     - **Screen Unlock Greeting**: *"Na-unlock na ang screen, aking Kapamilya! Maligayang pagbabalik, nandito lang ako para sa iyo!"*
5. **Strict Official Naming Rule & Repository Map (Always Prefix with `Kapamilya` — Never Use Old Names)**:
   - `Serena` / `Serena Screen Reader` and `Serena Mail` are **old names** (`昔の名前`) and MUST NEVER be used without **`Kapamilya`** at the beginning.
   - Current official names ALWAYS begin with **`Kapamilya`**:
     - **`Kapamilya Serena`** (`com.kapamilya.serena`) — Main Android repository pushed to GitLab **`https://gitlab.com/kapamilya/serena.git`** (Local clone at **`C:\echo\cocoa`** and synced with **`c:\echo\echo`**).
     - **`Kapamilya Serena Mail`** (`com.kapamilya.serena.mail`) — Pushed to GitLab **`https://gitlab.com/kapamilya/serena.git`** (`mail/` module in `C:\echo\cocoa`).
     - **`Kapamilya Serena for Windows`** — Pushed to GitLab **`https://gitlab.com/kapamilya/kapamilya-serena-windows.git`** (`C:\echo\kapamilya-serena-windows`).
     - **`Kapamilya Voice`** — Pushed to GitLab **`https://gitlab.com/kapamilya/kapamilya-voice.git`** (`C:\echo\kapamilya-voice`).
     - **Official Website & Music Player (GitLab Pages)** — Pushed exclusively to **`https://gitlab.com/kapamilya/kapamilya.gitlab.io.git`** (`https://kapamilya.gitlab.io`) from `C:\echo\shinji5683.github.io` (unified single official portal; duplicate full-name site removed).

---

## 1. Mandatory Agent Automation Rule (GitHub Push Suspended -> Local Commit/Build + GitLab Push)
While the legacy GitHub account (`shinji5683`) is suspended, **DO NOT run `git push` to GitHub (`github.com`)**. Instead:
- Commit locally (`git commit`), perform builds locally when needed, and push to the official **GitLab (`gitlab.com/kapamilya/...`)** remotes (`gitlab` / `origin`).

1. **Automated One-Command Pipeline (Windows PowerShell)**:
   - Run `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\auto_build_release.ps1 -CommitMessage "<message>"`
   - This script automatically stages, commits, pushes to all configured GitHub remotes (`origin` and `shinji`), builds the Release & Debug APKs locally if the Android SDK is installed, and uploads them to GitHub Releases.
2. **Cloud-Native GitHub Actions CI/CD (`.github/workflows/android-release.yml` & `build_and_release.yml`)**:
   - Even on a machine **without** Android Studio, Java, or the Android SDK installed, simply running `git commit` and `git push` to `main` (or pushing a `v*` tag) will automatically spin up an Ubuntu runner in GitHub Actions, compile the Release and Debug APKs, and publish/update the GitHub Release page automatically.

---

## 2. Project Structure & Key Entry Points
- `app/src/main/java/com/echosight/screenreader/`: Android Native Accessibility Service, TTS, Braille, OCR, Face Recognition, 47-Prefecture Dialect Greetings & Hourly Chime
  - Service Entry Point: `com.echosight.screenreader.service.EchoScreenReaderService`
  - TTS & Haptics Engine: `com.echosight.screenreader.tts.EchoTTSManager`
  - UI & Permission Dialogs: `com.echosight.screenreader.ui.MainActivity`
  - Auto-Update Engine: `com.echosight.screenreader.update.UpdateChecker`
- `app/src/main/res/`: Accessibility Service Configuration & High-Contrast WCAG AAA Resources
- `.github/workflows/android-release.yml`: Automated Cloud Build & GitHub Release Workflow
- `scripts/auto_build_release.ps1`: Portable Local/Cloud Commit, Push, Build & Release Automation Script
---

## 3. Mandatory Official Website (`kapamilya.gitlab.io`) & Eternal Post-Success Download Link Auto-Update Rule
Whenever any new software, NVDA add-on, Antigravity plugin, or tool is created or updated, OR whenever any new build is pushed, OR whenever any new song is released:

1. **Eternal Post-Success Download Link & Build Metadata Auto-Update Rule (`https://kapamilya.gitlab.io`)**:
   - **STRICT CONDITION (未来永劫の鉄則)**: Whenever a new build or code change for **Kapamilya Serena (Android or Windows)**, **Kapamilya Serena Mail**, **Kapamilya Voice**, or **Kiku** is built and pushed to GitLab, **ONLY AFTER verifying that the build and `git push` have succeeded**, the AI agent MUST immediately and automatically update the download links (`btn-download` / `permalink/latest` / package registry / artifact URLs), version numbers, and build metadata on Shinji's official website (`C:\echo\shinji5683.github.io\index.html` and `README.md`) across all 4 languages (`ja`, `en`, `tl`, `es`) and JSON-LD `@graph`, then commit and push to `https://gitlab.com/kapamilya/kapamilya.gitlab.io.git` (`gitlab-group main`).
   - A new build being pushed while the official download page points to an old build is strictly forbidden.
   - **Public Wording Rule for `Bantay ng Dangal`**: While this `AGENTS.md` preserves the internal Sacred Covenant of the CHR, all public-facing web pages, READMEs, licenses, and app strings across all languages MUST use the unlocalized Tagalog phrase **`Bantay ng Dangal`** (尊厳の番人) instead of the state organ name `CHR`.
   - Current flagship software lineup on `kapamilya.gitlab.io`:
     - **① Kapamilya Serena** (`com.kapamilya.serena` — Android 11–17+ Screen Reader)
     - **② Kapamilya Serena Mail** (`com.kapamilya.serena.mail` — On-Device Gemma 4 AI Gmail Client)
     - **③ Kiku (`聞く` / `kiku-package`)** — Google Antigravity Zero-Jargon Interview & GO-Sign Gate Plugin (`/kiku` & `/kiku-on`)
     - **④ Kapamilya Voice for NVDA (`kapamilyaVoice` / `kapamilya/kapamilya-voice`)** — Multi-Engine NVDA Speech Synthesizer & Global Switcher (Open JTalk / Natural Voice / Windows OneCore / eSpeak-NG Chinese Coexistence / Unicode Multilingual Auto-Router / 7-Dialect English & Taglish)
     - **⑤ Kapamilya Serena for Windows (`kapamilya/kapamilya-serena-windows`)** — 100% Standalone Native Windows AI Screen Reader (Zero NVDAJP Dependency / UI Automation / Startup, Shutdown & Lock Chimes + Tagalog Greetings / Spatial Position & Cropped Image AI Explainer / JIS X 0208 Multilingual Voice Router)
     - **⑥ Kapamilya Serena Mail for Windows (`kapamilya/serena` — `mailer-windows/`)** — Native Windows Accessibility Gmail Client (Google OAuth2 / RFC 6154 7 Folders / OpenPGP Digital Signing & Verification / RFC 8098 Read Receipts MDN / Gemma 4 On-Device AI)
2. **Automatic Music Player Sync for Every New Song Release**:
   - The embedded multi-platform music player in `C:\echo\shinji5683.github.io\index.html` includes both:
     - **Live Client-Side Auto-Discovery**: Queries Apple Music / iTunes Lookup API (`artistId=6785027836`) and `tracks.json` dynamically on page load, automatically adding any newly released songs by **Shinji Sakiyama** to `<select id="track-select">` and `<ul class="track-list">` in real time.
     - **Automated GitHub Actions Sync (`.github/workflows/auto_sync_music.yml`)**: Periodically fetches new releases from Shinji Sakiyama's YouTube / Apple Music / Spotify channels and updates the catalog automatically.

---

## 4. Mandatory Inno Setup 7.x+ Latest Development Build Rule for All Windows Applications (Tribute to Tomato-san of Fukushima / 福島県のトマトさんへの敬意)
- **STRICT RULE FOR WINDOWS INSTALLERS**: Whenever creating or updating any Windows application in the **Kapamilya Serena** ecosystem (including **`Kapamilya Serena for Windows`**, **`Kapamilya Serena Mail for Windows`**, and any future Windows applications), the installer MUST ALWAYS be built using the **latest development version / latest commit of Inno Setup 7.x+ (`jrsoftware/issrc`, installed at `C:\Tools\InnoSetup7\ISCC.exe`)** — NEVER legacy NSIS.
- **Why We Honor Inno Setup 7.x+**: Shinji's friend **Tomato-san (トマトさん)** in Fukushima Prefecture discovered and reported a bug in Inno Setup, and the Inno Setup developers responded and fixed it immediately. In tribute to Tomato-san's contribution and the active, responsive craftsmanship of the Inno Setup maintainers, all Windows installers in this workspace permanently adopt **Inno Setup 7.x+ (incorporating the latest commits from `https://github.com/jrsoftware/issrc`)**, complete with `PlaySoundW@winmm.dll` loop BGM playback (`kapamilya_installer_music.wav`) and full screen-reader accessibility.

---

## 5. Mandatory Version Increment Rule on Every Update & Highest-Quality Natural Multilingual TTS Routing Rule
- **STRICT VERSION INCREMENT RULE (アップデート＝必ずバージョン番号更新の鉄則)**:
  - Whenever ANY code change, feature update, bug fix, or new build is released for any application in the **Kapamilya Serena** ecosystem (`Kapamilya Serena`, `Kapamilya Serena Mail`, `Kapamilya Serena for Windows`, `Kapamilya Serena Mail for Windows`, `Kapamilya Voice`, `Kiku`), **you MUST ALWAYS increment the version number** (`versionName` & `versionCode` in `build.gradle.kts`, `CURRENT_VERSION` in C# sources, `version.txt`, `MyAppVersion` in Inno Setup `.iss` scripts, GitLab Generic Package Registry version paths, and official website download links/labels on `kapamilya.gitlab.io`).
  - Releasing an update while keeping the same version number is strictly forbidden (`アップデートがされるイコールバージョン番号が変わる`).
- **STRICT HIGHEST-QUALITY NATURAL MULTILINGUAL TTS ROUTING RULE**:
  - Signature `Kapamilya Serena` menu titles (`Mata ng Kapamilya: Live Tagapagsalaysay`, `Mata ng Kapamilya: Tagasuri ng Paninda`, `Bayanihan Gabay: Tagabasa ng Dokumento at Talaan`, `Kapamilya Bayanihan Link`) and their activation/deactivation voice prompts MUST be unified in pure Tagalog and routed directly to the highest-quality natural Tagalog TTS (`fil-PH` / `tl-PH` / Natural `Blessica` / Piper Tagalog Female).
  - For all normal OS home screens, web pages, emails, and AI analysis body texts, every language segment (Tagalog, Japanese, English, Spanish, etc.) MUST automatically route to the **highest-quality natural voice** installed for that specific language.

