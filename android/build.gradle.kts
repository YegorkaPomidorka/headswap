buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Kadb 2.1.5 (пейринг активации с телефона) собран Kotlin 2.4: встроенный в AGP 9.4.1 Kotlin 2.2.10 его метаданные не читает.
        // AGP берёт KGP из classpath скрипта, поэтому пин здесь же; compose-плагин ниже той же версии.
        // https://developer.android.com/build/releases/agp-9-0-0-release-notes
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    // версия = KGP из buildscript выше; лаунчер Headswap Desktop на Compose
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
