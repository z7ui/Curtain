import java.util.Properties

plugins {
    id("com.android.application")
}

// 正式签名：密钥在仓库根目录 keystore/ 下（已 gitignore）。
// 没有这个文件时自动退回 debug 签名，保证别人 clone 下来也能构建。
val keystorePropsFile = rootProject.file("keystore/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasReleaseKey = keystorePropsFile.exists()

android {
    namespace = "com.example.curtain"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.example.curtain"
        minSdk = 27
        targetSdk = 35
        versionCode = 7
        versionName = "1.7"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                // storeFile 可能是裸文件名（相对 keystore/ 目录），也可能是仓库内相对路径
                val storePath = keystoreProps.getProperty("storeFile")
                storeFile = rootProject.file(storePath).takeIf { it.exists() }
                    ?: keystorePropsFile.parentFile.resolve(storePath)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
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
            // 有正式密钥就用正式密钥；没有则退回 debug（本地/CI 都能出包）
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
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