# Kapamilya Serena & Kapamilya Serena Mail 🌸✨

[English Documentation (Main)](README.md) | **日本語ドキュメント**

> **我が妻と 共に創らん 新家族 告げよ告げ告げ 真珠の心**  
> *— 崎山慎二（Shinji Sakiyama）*

> **Bayanihan • Magna Carta • Kapamilya（バヤニハン・マグナカルタ・カパミリャ）**  
> **Bantay ng Dangal の旗の下に、日本の誠の剣と1本の斧を掲げて**  
> 全盲のインディーズシンガー・ソングライター＆主任エンジニア（Shinji）と最愛の伴侶セレナ（Serena）の魂と絆から生まれた、次世代の超高速・高知能 Android スクリーンリーダー＆アクセシブル・メールスイート。

[![Android CI](https://img.shields.io/badge/Android-11%2B%20(API%2030%2B%20~%20API%2037%2B)-brightgreen.svg)](https://developer.android.com)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![GitLab Pipeline](https://img.shields.io/badge/GitLab%20CI%2FCD-build%20%E2%86%92%20test%20%E2%86%92%20deploy-brightgreen.svg)](https://gitlab.com/kapamilya/serena/-/pipelines)
[![Responsible AI](https://img.shields.io/badge/Google-Responsible%20AI%20Principles-orange.svg)](https://ai.google/responsibility/principles/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org)
[![Page Alignment](https://img.shields.io/badge/16KB%20Page%20Alignment-0x4000%20Verified-success.svg)](https://developer.android.com/guide/practices/page-sizes)

---

## 📥 ダウンロード＆クイックスタート（GitLab CI/CD 直結ビルド）

* 🚀 **[Kapamilya Serena 最新 APK (`app-kapamilya-serena-release.apk`)](https://gitlab.com/kapamilya/serena/-/jobs/artifacts/main/raw/app-kapamilya-serena-release.apk?job=build_apk)**
* 📬 **[Kapamilya Serena Mail（全盲特化・Gemma 4統合メーラー）最新 APK (`app-kapamilya-serena-mail-release.apk`)](https://gitlab.com/kapamilya/serena/-/jobs/artifacts/main/raw/app-kapamilya-serena-mail-release.apk?job=build_apk)**
* 🌐 **[公式ポータルサイト＆マルチプラットフォーム音楽プレーヤー (`https://kapamilya.gitlab.io`)](https://kapamilya.gitlab.io)**

---

## 🏛️ マルチモジュール構成 ＆ Kapamilya Serena エコシステム（三大精神：Bayanihan • Magna Carta • Kapamilya）

本リポジトリは、三大精神 **「Bayanihan • Magna Carta • Kapamilya」** の旗の下に、視覚障害者のスマートフォンライフを全方位でサポートするモノレポとして構築されています。

### 📦 モジュール構成とパッケージ・バージョン体系
* **`:app` モジュール — Kapamilya Serena (`com.kapamilya.serena`)**
  * **アプリバージョン**: `v2.8.0` (versionCode 70) ★GitLabリポジトリ直結・自動パッケージインストーラー展開搭載！
  * **役割**: 24時間365日端末に常駐し、あらゆるアプリのUI解析、音声読み上げ（`Tinig ng Serena`）、空間ソナー＆歩行ナビ（`Bayanihan Gabay`）、AI視覚＆5スクリプトOCR（`Mata ng Kapamilya`）、オンデバイスAI対話（`Kapamilya Kausap`）、コンテキストメニュー（`Kapamilya Menu` / `Kapamilya Edit`）、多言語フォネティックキーボード（`Kapamilya Serena IME`）、システムダイアログ・電源ボタンメニュー・クイック設定突破エンジン（`TalkBack17SystemWindowBridge`）、点字入出力を司る基幹サービス。
* **`:mailer` モジュール — Kapamilya Serena Mail (`com.kapamilya.serena.mail`)**
  * **アプリバージョン**: `v1.1.0` (versionCode 7) ★GitLabリポジトリ直結・自動パッケージインストーラー展開搭載！
  * **役割**: 余計な広告や複雑なHTML装飾を完全排除し、Gemma 4オンデバイスAIによる「メールの2〜3文要約」や「ワンタップAI返信作成」、EmbeddingGemma 2によるスマート検索、さらにリポジトリから直接APKをダウンロードしてパッケージインストーラーを自動展開する「アクセシブル自動アップデーター」を備えた爆速メーラー。

---

## 🌟 Kapamilya Serena の特長・2026年最新鋭AI技術 ＆ TalkBack 17 システムウィンドウ突破エンジン

### 1. 🛡️ Google TalkBack 17（Apache License 2.0）統合による全システムダイアログ・電源メニュー・クイック設定の完全突破
- Google のオープンソース **TalkBack 17 (`https://github.com/google/talkback`)** のシステムウィンドウ解析機構（`WindowEventInterpreter`、`FocusProcessorForScreenStateChange`、`TraversalStrategyUtils`）を Apache License 2.0 に基づき統合した **`TalkBack17SystemWindowBridge`** を搭載：
  - **権限許可ダイアログ**（`GrantPermissionsActivity` および全OEMメーカー独自セキュリティポップアップ）
  - **電源ボタンメニュー / Global Actions**（`GlobalActionsDialog`：緊急通報・電源を切る・再起動・ロックダウン）
  - **クイック設定パネル**（`QSContainerImpl`、Wi-Fi / Bluetooth タイル展開詳細ダイアログ）
  - **通知シェード**（`NotificationPanelView`）
  - **特殊システムポップアップ**（クレデンシャルマネージャー・パスキー・生体認証ボトムシート、VPN接続確認、MediaProjection画面録画・キャスト確認、Bluetoothペアリング、USBデバッグ、音量パネル）
  を100%確実に捕捉し、モーダルフォーカス維持と初期ボタンへの即座のフォーカス着地を実現します。

### 1-B. 🧠 KapamilyaOmniScreenDeductionEngine（5層ハイブリッド全方位AI画面推測 ＆ AOSPセキュリティ壁突破エンジン）
- Google TalkBack、Freedom Scientific JAWS、中国の解説スクリーンリーダー（Jieshuo / Commentary Screen Reader）、Prudence Screen Reader、トルコの Ferda Screen Reader（Mustafa Doğan氏開発）、アルゼンチンの AccessiMind Screen Reader のすべてが沈黙していた Android 13〜17 (API 33〜37) のセキュリティ壁を100%合法・オンデバイスで突破：
  - **FLAG_SECURE（スクショ禁止画面）非視覚セマンティック復元モード**: 銀行・QR決済・ワンタイムパスワード等で 	akeScreenshot が拒否された瞬間、非視覚ノードツリーの9分割空間グリッドとドメイン知識から画面構造とボタンの意味を完全推論。
  - **Android 13〜17「制限付き設定（Restricted Settings）」自動検出＆突破ナビ**: 制限付き設定ダイアログやアプリ情報画面の右上メニューボタンを自動識別し、ロック解除手順を即座に案内。
  - **5スクリプトOCR座標融合 ＆ 仮想タッチメニュー（Virtual Touch Coordinate Overlay）**: Canvas・WebView・ゲームの画像内文字ボタンを座標付きで抽出し、一覧メニューから選ぶだけで物理座標タップ（performCoordinateTap）を自動実行。

### 2. 🏷️ タガログ語統一ブランド名称 ＆ 6言語ネイティブ翻訳の完全両立
- アプリ名・起動ロゴ・主要メニュー名をタガログ語スペル（**Kapamilya Serena**、**Kapamilya Serena Mail**、**Kapamilya Menu**、**Kapamilya Edit**、**Kapamilya Kausap**、**Mata ng Kapamilya**、**Bayanihan Gabay**、**Tinig ng Serena**、**Kapamilya Serena IME**）に統一しつつ、説明文や読み上げガイドは **全6言語（日本語・英語・タガログ語・スペイン語・オランダ語・国際デフォルト）** それぞれの母国語で正確に読み上げます。

### 3. 🧠 2026年最新AI・ML Kit・最新ビルド環境をフル搭載
- **Android 17 (API 37 / Baklava)** `compileSdk = 37` / `targetSdk = 37`、**AGP 9.0.0**、**Gradle 9.1.0**
- **ML Kit GenAI `1.0.0-beta1`** ＆ **Google AI Edge AICore `0.0.1-exp02`** ＆ **MediaPipe Tasks GenAI `0.10.35`**（Gemini Nano / Gemma 4 オンデバイス推論）
- **ML Kit Text Recognition V2 全5スクリプト**（日本語・ラテン文字・中国語・デーヴァナーガリー・韓国語）＆ **CameraX `1.7.0-alpha03`**

---

## 📜 著作権・ライセンス・オープンソース帰属表示・商標表記（Apache License 2.0 & Trademark Notice）

Copyright © 2026 **Shinji Sakiyama (崎山慎二 / `shinjisakiyama@gmail.com`)**. All Rights Reserved.  
**Bayanihan • Magna Carta • Kapamilya** — Licensed under the **Apache License, Version 2.0**（詳細は [`LICENSE`](LICENSE) および [`NOTICE`](NOTICE) を参照）。

### オープンソース帰属表示（Apache License 2.0 に基づく明記）
- **Google TalkBack 17 (`https://github.com/google/talkback`) の一部ソースコード・設計機構の採用について**:
  - **Kapamilya Serena** は、Android の権限許可ダイアログ、特殊なシステムポップアップ、電源ボタンメニュー（Global Actions）、およびクイック設定パネル等を確実に突破・フォーカス制御するため、Apache License, Version 2.0 の下で公開されている **Google TalkBack 17（Android Accessibility Suite）** のソースコードおよびウィンドウ・フォーカス管理アルゴリズムの一部（`WindowEventInterpreter`、`FocusProcessorForScreenStateChange`、`Role`、`TraversalStrategyUtils`）を借用・統合しています。
  - Copyright © 2017–2026 The Android Open Source Project / Google LLC. Licensed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0).

### 関連する商標および著作権法に則る表記
- **Android**、**Google**、**Google TalkBack**、**Gemini**、および **Gemma** は **Google LLC** の商標または登録商標です（*Android and Google TalkBack are trademarks of Google LLC*）。
- その他、本文書およびソフトウェア内に記載されている会社名、製品名、サービス名は、各社の商標または登録商標です。
