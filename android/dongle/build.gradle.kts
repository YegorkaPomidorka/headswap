import java.util.Properties

plugins {
    id("com.android.application")
}

// Тот же ключ, что у Headswap (см. app/build.gradle.kts), если он есть на машине
val releaseProps = Properties().apply {
    val f = file("${System.getProperty("user.home")}/.android-keys/headswap-release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.yegorkapomidorka.headswap.dongle"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yegorkapomidorka.headswap.dongle"
        // Стики RK3066 (UG007, MK808) живут на Android 4.1
        minSdk = 16
        targetSdk = 22
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (releaseProps.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // targetSdk 22 намеренно: на 4.1 и без runtime-разрешений
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
        abortOnError = false
    }
}
