plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.curtain"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.example.curtain"
        minSdk = 27
        targetSdk = 35
        versionCode = 6
        versionName = "1.6"
    }

    buildTypes {
        debug {
            // 方便直接装到手机上对比
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 本地调试用 debug 签名，方便直接安装
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    // 原来用的是 core-ktx，但这个工程是纯 Java，换成 core 能少带一个 kotlin-stdlib
    implementation("androidx.core:core:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
}
