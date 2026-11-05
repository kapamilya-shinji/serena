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
    namespace = "com.kapamilya.serena.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kapamilya.serena.phone"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "5.14.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "DEVELOPER_NAME", "\"Shinji Sakiyama\"")
        buildConfigField("String", "DEVELOPER_EMAIL", "\"shinjisakiyama@gmail.com\"")
        buildConfigField("String", "COPYRIGHT_NOTICE", "\"Copyright © 2026 Shinji Sakiyama. All Rights Reserved. Bayanihan • Magna Carta • Kapamilya\"")
        buildConfigField("String", "PRIMARY_LLM_MODEL", "\"Gemma 4 (google-deepmind/gemma-4-it-int4)\"")
        buildConfigField("String", "PRIMARY_EMBEDDING_MODEL", "\"EmbeddingGemma 2 (google-deepmind/embedding-gemma-2-multimodal-q4)\"")
    }

    signingConfigs {
        create("release") {
            val keystorePath = localProps.getProperty("SERENA_STORE_FILE")
                ?: System.getenv("SERENA_STORE_FILE")
                ?: "serena-release-key.jks"
            storeFile = rootProject.file("app/$keystorePath")
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
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
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
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.activity:activity-ktx:1.14.0-alpha03")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.12.0-alpha05")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // ML Kit Face Detection & Image Labeling for Real-Time Video Call Expression & Camera Framing Narration
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:image-labeling:17.0.9")

    // ML Kit GenAI Bleeding-Edge (Embedding, Prompt, Agent, Summarization, Rewriting, Proofreading, Image Description)
    implementation("com.google.mlkit:genai-common:1.0.0-beta5")
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta5")
    implementation("com.google.mlkit:genai-agent:1.0.0-alpha1")
    implementation("com.google.mlkit:genai-embedding:1.0.0-alpha1")
    implementation("com.google.mlkit:genai-summarization:1.0.0-beta1")
    implementation("com.google.mlkit:genai-rewriting:1.0.0-beta1")
    implementation("com.google.mlkit:genai-proofreading:1.0.0-beta1")
    implementation("com.google.mlkit:genai-image-description:1.0.0-beta1")

    // Google AI Edge / Gemma 4 / EmbeddingGemma 2 / LiteRT-LM / LocalAgents / AICore / MediaPipe Tasks
    implementation("com.google.ai.edge.aicore:aicore:0.0.1-exp02")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    implementation("com.google.ai.edge.localagents:localagents-fc:0.1.0")
    implementation("com.google.ai.edge.localagents:localagents-rag:0.3.0")
    implementation("com.google.mediapipe:tasks-core:1.1.1")
    implementation("com.google.mediapipe:tasks-genai:0.10.35")
    implementation("com.google.mediapipe:tasks-text:1.1.1")
    implementation("com.google.mediapipe:tasks-audio:1.1.1")
    implementation("com.google.mediapipe:tasks-retrieval:1.1.1")
}
