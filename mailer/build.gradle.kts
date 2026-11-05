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
    namespace = "com.kapamilya.serena.mail"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kapamilya.serena.mail"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "5.9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "DEVELOPER_NAME", "\"Shinji\"")
        buildConfigField("String", "DEVELOPER_EMAIL", "\"shinjisakiyama@gmail.com\"")
        buildConfigField("String", "COPYRIGHT_NOTICE", "\"Copyright © 2026 Shinji. All Rights Reserved. Bayanihan • Magna Carta • Kapamilya\"")
        buildConfigField("String", "AI_ENGINE_ATTRIBUTION", "\"Gemma 4 + EmbeddingGemma 2 (Developed by Google DeepMind)\"")
        buildConfigField("String", "GITLAB_PROJECT_URL", "\"https://gitlab.com/kapamilya/serena\"")
        buildConfigField("String", "REPO_RELEASE_TAG", "\"v5.9.0\"")

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
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            pickFirsts += "**/*.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/{NOTICE.md,LICENSE.md,NOTICE,LICENSE}"
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
    // AndroidX Core & UI Bleeding-Edge
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.activity:activity-ktx:1.14.0-alpha03")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.12.0-alpha05")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.12.0-alpha05")
    implementation("androidx.security:security-crypto:1.1.0")

    // Coroutines Latest
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Google Sign-In & Google Auth (Play Services Latest with GoogleSignIn API)
    implementation("com.google.android.gms:play-services-auth:21.5.1")

    // JavaMail API for Android (IMAP / SMTP for Gmail)
    implementation("com.sun.mail:android-mail:1.6.8")
    implementation("com.sun.mail:android-activation:1.6.8")

    // Google AI Edge / Gemma 4 / EmbeddingGemma 2 / LiteRT-LM / LocalAgents / AICore / ML Kit GenAI Bleeding-Edge
    implementation("com.google.mediapipe:tasks-core:1.1.1")
    implementation("com.google.mediapipe:tasks-genai:0.10.35")
    implementation("com.google.mediapipe:tasks-text:1.1.1")
    implementation("com.google.mediapipe:tasks-audio:1.1.1")
    implementation("com.google.mediapipe:tasks-retrieval:1.1.1")
    implementation("com.google.ai.edge.aicore:aicore:0.0.1-exp02")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    implementation("com.google.ai.edge.localagents:localagents-fc:0.1.0")
    implementation("com.google.ai.edge.localagents:localagents-rag:0.3.0")
    implementation("com.google.mlkit:genai-common:1.0.0-beta5")
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta5")
    implementation("com.google.mlkit:genai-agent:1.0.0-alpha1")
    implementation("com.google.mlkit:genai-embedding:1.0.0-alpha1")
    implementation("com.google.mlkit:genai-summarization:1.0.0-beta1")
    implementation("com.google.mlkit:genai-rewriting:1.0.0-beta1")
    implementation("com.google.mlkit:genai-proofreading:1.0.0-beta1")
    implementation("com.google.mlkit:genai-image-description:1.0.0-beta1")

    // Unit Testing
    testImplementation("junit:junit:4.13.2")
}
