plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 固定签名密钥库位于仓库根目录（由 CI 检出后即可用）
val releaseKeystoreFile: File = file("${project.rootDir}/release.keystore")

android {
    namespace = "com.woshiyigemanhuajia.btpopup"
    compileSdk = 36

    defaultConfig {
        //
        // 【包名保持本 App 原样】
        // 曾用 com.haooz.chedule（课表包名）验证白名单假设，但用户同时要用课表，
        // 同包名无法共存，故回退。改走「把本包名写进系统白名单」这条路。
        //
        applicationId = "com.woshiyigemanhuajia.btpopup"
        minSdk = 26
        targetSdk = 36
        versionCode = 53
        versionName = "1.7.7"
        resConfigs("zh", "en")
    }

    //
    // 签名改用「软大课表」的专用密钥 keystore/softbig.jks。
    //
    // 为什么：源码已与课表逐字对齐、断网绕过路径也一致，但课表能上岛、本 App 不能。
    // 包名不能改（用户要同时用课表），剩下最可能的校验维度就是**签名** ——
    // 小米超级岛白名单常常按签名放行，换成同一把密钥即可获得相同身份。
    //
    signingConfigs {
        create("softbig") {
            storeFile = rootProject.file("softbig.jks")
            storePassword = "softbig2026"
            keyAlias = "softbig"
            keyPassword = "softbig2026"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("softbig")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("softbig")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        // Shizuku 特权服务（上岛断网 xmsf 用）需要 AIDL
        aidl = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
