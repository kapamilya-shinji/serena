import java.util.Properties

plugins {
    id("com.android.application")
}

val localProps = Properties().apply {
    val propFile = rootProject.file("local.properties")
    if (propFile.exists()) {
        propFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.kapamilya.serena"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kapamilya.serena"
        minSdk = 26
        targetSdk = 36
        versionCode = 84
        versionName = "5.11.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "DEVELOPER_NAME", "\"Shinji\"")
        buildConfigField("String", "DEVELOPER_EMAIL", "\"shinjisakiyama@gmail.com\"")
        buildConfigField("String", "DEVELOPER_PHONE", "\"+818094959134\"")
        buildConfigField("String", "DEVELOPER_PHONE_DISPLAY", "\"080-9495-9134\"")
        buildConfigField("String", "COPYRIGHT_NOTICE", "\"Copyright © 2026 Shinji. All Rights Reserved. Bayanihan • Magna Carta • Kapamilya\"")
        buildConfigField("String", "AI_ENGINE_ATTRIBUTION", "\"Gemma 4 (Developed by Google DeepMind)\"")
        buildConfigField("String", "GITLAB_PROJECT_URL", "\"https://gitlab.com/kapamilya/serena\"")
    }

    signingConfigs {
        create("release") {
            val keystorePath = localProps.getProperty("SERENA_STORE_FILE")
                ?: System.getenv("SERENA_STORE_FILE")
                ?: "serena-release-key.jks"
            storeFile = file(keystorePath)
            storePassword = localProps.getProperty("SERENA_STORE_PASSWORD")
                ?: System.getenv("SERENA_STORE_PASSWORD")
                ?: ""
            keyAlias = localProps.getProperty("SERENA_KEY_ALIAS")
                ?: System.getenv("SERENA_KEY_ALIAS")
                ?: ""
            keyPassword = localProps.getProperty("SERENA_KEY_PASSWORD")
                ?: System.getenv("SERENA_KEY_PASSWORD")
                ?: ""
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            val releaseSign = signingConfigs.getByName("release")
            if (releaseSign.storeFile?.exists() == true && !releaseSign.storePassword.isNullOrEmpty()) {
                signingConfig = releaseSign
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            val releaseSign = signingConfigs.getByName("release")
            if (releaseSign.storeFile?.exists() == true && !releaseSign.storePassword.isNullOrEmpty()) {
                signingConfig = releaseSign
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += "**/libimage_processing_util_jni.so"
            pickFirsts += "**/*.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
            pickFirsts += "META-INF/**"
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

tasks.matching { it.name.endsWith("AarMetadata") }.configureEach {
    enabled = false
}

configurations.all {
    resolutionStrategy {
        force(
            "org.jetbrains.kotlin:kotlin-stdlib:2.5.0-Beta2",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.5.0-Beta2",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.5.0-Beta2",
            "org.jetbrains.kotlin:kotlin-stdlib-common:2.5.0-Beta2"
        )
    }
}

dependencies {
    // AndroidX & Jetpack Core Latest (Preview / Bleeding-Edge Alpha/Beta Phase)
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.activity:activity-ktx:1.14.0-alpha03")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.12.0-alpha05")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.12.0-alpha05")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.12.0-alpha05")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Jetpack Compose & Material 3 (M3) Latest Preview & Beta Phase
    implementation("androidx.compose.material3:material3:1.5.0-beta02")
    implementation("androidx.compose.ui:ui:1.13.0-beta02")
    implementation("androidx.compose.ui:ui-tooling-preview:1.13.0-beta02")

    // Google Play Services & ML Kit On-Device Multi-Script OCR, Vision, & GenAI Bleeding-Edge
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    implementation("com.google.android.gms:play-services-mlkit-face-detection:17.1.0")
    implementation("com.google.mlkit:face-mesh-detection:16.0.0-beta3")
    implementation("com.google.mlkit:object-detection:17.0.2")
    implementation("com.google.mlkit:object-detection-custom:17.0.2")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:image-labeling-custom:17.0.3")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:digital-ink-recognition:19.0.0")
    implementation("com.google.mlkit:smart-reply:17.0.4")
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    implementation("com.google.mlkit:pose-detection-accurate:18.0.0-beta5")
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")

    // ML Kit GenAI Bleeding-Edge (Gemini Nano On-Device Prompt, Agent, Embedding, Speech, Summarization, Proofreading, Rewriting, Image Description)
    implementation("com.google.mlkit:genai-common:1.0.0-beta5")
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta5")
    implementation("com.google.mlkit:genai-agent:1.0.0-alpha1")
    implementation("com.google.mlkit:genai-embedding:1.0.0-alpha1")
    implementation("com.google.mlkit:genai-speech-recognition:1.0.0-alpha2")
    implementation("com.google.mlkit:genai-speech-translation:1.0.0-alpha1")
    implementation("com.google.mlkit:genai-text-to-speech:1.0.0-alpha1")
    implementation("com.google.mlkit:genai-summarization:1.0.0-beta1")
    implementation("com.google.mlkit:genai-proofreading:1.0.0-beta1")
    implementation("com.google.mlkit:genai-rewriting:1.0.0-beta1")
    implementation("com.google.mlkit:genai-image-description:1.0.0-beta1")

    // Google AI Edge / Gemma 4 / LiteRT-LM / LocalAgents / AICore Experimental / MediaPipe Tasks Latest
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")
    implementation("com.google.ai.edge.aicore:aicore:0.0.1-exp02")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    implementation("com.google.ai.edge.localagents:localagents-fc:0.1.0")
    implementation("com.google.ai.edge.localagents:localagents-rag:0.3.0")
    implementation("com.google.mediapipe:tasks-core:1.1.1")
    implementation("com.google.mediapipe:tasks-genai:0.10.35")
    implementation("com.google.mediapipe:tasks-vision:1.1.1")
    implementation("com.google.mediapipe:tasks-text:1.1.1")
    implementation("com.google.mediapipe:tasks-audio:1.1.1")
    implementation("com.google.mediapipe:tasks-decision:1.1.1")
    implementation("com.google.mediapipe:tasks-retrieval:1.1.1")

    // CameraX for Live Vision and Camera Capture Bleeding-Edge Alpha (v1.7.0-alpha03)
    implementation("androidx.camera:camera-core:1.7.0-alpha03")
    implementation("androidx.camera:camera-camera2:1.7.0-alpha03")
    implementation("androidx.camera:camera-lifecycle:1.7.0-alpha03")
    implementation("androidx.camera:camera-view:1.7.0-alpha03")
    implementation("androidx.camera:camera-extensions:1.7.0-alpha03")
    implementation("androidx.camera:camera-mlkit-vision:1.7.0-alpha03")

    // Unit Testing
    testImplementation("junit:junit:4.13.2")
}
