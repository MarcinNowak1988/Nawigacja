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

if (androidSdkAvailable) {
    // Rozszerzenie `android` konfigurujemy dynamicznie, **bez odwoływania się do typów AGP**.
    //
    // Plugin jest tu nakładany imperatywnie (`apply(plugin = ...)`), więc nie ma akcesora
    // `android { }` z bloku `plugins`. Nie da się też sięgnąć po `LibraryExtension`: ten plik
    // musi kompilować się także wtedy, gdy AGP nie ma na classpath, a wyniesienie konfiguracji
    // do skryptu ładowanego przez `apply(from = ...)` nie działa — taki skrypt dostaje tylko
    // część classpathu AGP (`com.android.build.gradle` widać, `com.android.build.api.dsl` już nie).
    //
    // `withGroovyBuilder` należy do Gradle Kotlin DSL, więc kompiluje się zawsze, a wykonuje
    // wyłącznie w tej gałęzi — gdy plugin faktycznie jest nałożony.
    extensions.getByName("android").withGroovyBuilder {
        setProperty("namespace", "pl.reactivebike.shared")
        setProperty("compileSdk", property("androidCompileSdk").toString().toInt())

        "defaultConfig" {
            setProperty("minSdk", property("androidMinSdk").toString().toInt())
        }

        "compileOptions" {
            setProperty("sourceCompatibility", JavaVersion.VERSION_17)
            setProperty("targetCompatibility", JavaVersion.VERSION_17)
        }
    }
}
