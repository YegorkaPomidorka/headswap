import java.util.Properties

plugins {
    id("com.android.application")
}

// Release key lives outside the project: %USERPROFILE%\.android-keys\headswap-release.properties
val releaseProps = Properties().apply {
    val f = file("${System.getProperty("user.home")}/.android-keys/headswap-release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// ./gradlew assembleDebug -PcarlifePackage: сборка под пакет настоящего CarLife для старых ГУ с запуском по ADB
// (только наличие свойства, значение не важно: -PcarlifePackage без значения даёт пустую строку)
val carlifeAdb = project.hasProperty("carlifePackage")

android {
    namespace = "com.yegorkapomidorka.headswap"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yegorkapomidorka.headswap"
        minSdk = 29
        targetSdk = 37
        versionCode = 55
        versionName = "2.4.0"
        // «По воздуху» (беспровод): с 2.0.0 включён во всех сборках; выключить для проверки: gradlew assembleRelease -Pwlbeta=false
        buildConfigField("boolean", "WIRELESS_BETA", (project.findProperty("wlbeta") != "false").toString())
        if (carlifeAdb) {
            // как у настоящего CarLife 8.6.7: ГУ может смотреть версию пакета
            applicationId = "com.baidu.carlife"
            versionCode = 259
            versionName = "8.6.7"
        }
        buildConfigField("boolean", "CARLIFE_ADB", carlifeAdb.toString())
        manifestPlaceholders["carlifeAdb"] = carlifeAdb.toString()
    }

    buildFeatures {
        buildConfig = true
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
        debug {
            buildConfigField("boolean", "WIRELESS_BETA", "true")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
