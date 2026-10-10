import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名信息放在项目根目录的 keystore.properties（不随代码分发）
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.dtu.cleaner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dtu.cleaner"
        minSdk = 30
        targetSdk = 35
        versionCode = 27
        versionName = "2.8.2"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (keystoreProps.isNotEmpty()) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    // 模型文件不压缩，运行时可以直接内存映射
    androidResources { noCompress += "tflite" }
    testOptions {
        unitTests.all {
            it.systemProperty("calibDir", System.getProperty("calibDir") ?: "")
            it.systemProperty("textTsv", System.getProperty("textTsv") ?: "")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // 离线内置模型版本：荣耀国行机没有 Google 服务也能用
    implementation("com.google.mlkit:face-detection:16.1.7")
    // 照片美感评分（NIMA 模型，Apache-2.0）。独立运行时，不依赖 Google 服务
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // 充电时在后台分析新照片、每月提醒
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // 视频压缩（4K → 1080p H.265），全在手机上转码
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("androidx.media3:media3-muxer:1.5.1")
    implementation("androidx.media3:media3-container:1.5.1")
    // 截图识字（中文离线模型）、二维码识别：都是内置模型，不需要 Google 服务
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // 读照片里的拍摄地点
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    testImplementation("junit:junit:4.13.2")
}
