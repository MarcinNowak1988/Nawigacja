import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// Warstwa androidowa jest opcjonalna — patrz komentarz w settings.gradle.kts.
// AGP trafia na classpath tylko wtedy, gdy SDK zostało wykryte (build.gradle.kts w korzeniu).
val androidSdkAvailable = rootProject.extra["androidSdkAvailable"] as Boolean

if (androidSdkAvailable) {
    apply(plugin = "com.android.library")
    // Konfiguracja rozszerzenia `android` mieszka w osobnym skrypcie, bo odwołuje się
    // do typów AGP — bez pluginu na classpath ten plik nie dałby się skompilować.
    apply(from = "android.gradle.kts")
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())

    jvm()

    // Targety iOS (`iosArm64`, `iosSimulatorArm64`) dojdą razem z modułem iOS —
    // Kotlin/Native kompiluje je wyłącznie na macOS z Xcode. Kod w `commonMain`
    // nie będzie wtedy wymagał zmian.
    if (androidSdkAvailable) {
        androidTarget {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
