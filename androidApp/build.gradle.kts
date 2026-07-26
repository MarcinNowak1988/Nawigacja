// Moduł dołączany wyłącznie wtedy, gdy Android SDK jest dostępne (settings.gradle.kts).
//
// Oba pluginy wskazujemy **bez wersji**. AGP trafia na classpath korzenia przez warunkowy
// blok `buildscript`, a plugin Kotlina przez `plugins { ... apply false }` w korzeniu.
// Podanie wersji tutaj kończy się błędem „plugin is already on the classpath with an
// unknown version" — Gradle nie ma jak sprawdzić zgodności z tym, co już zostało załadowane.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.reactivebike.android"
    compileSdk = property("androidCompileSdk").toString().toInt()

    defaultConfig {
        applicationId = "pl.reactivebike"
        minSdk = property("androidMinSdk").toString().toInt()
        targetSdk = property("androidTargetSdk").toString().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    // Podpisywanie wydania kluczem z sekretów CI, gdy są dostępne.
    //
    // Bez skonfigurowanego keystore `assembleRelease` podpisuje build kluczem debugowym.
    // Dzięki temu APK z każdego przebiegu jest instalowalny i da się go przetestować,
    // zamiast produkować niepodpisany artefakt, którego nie da się uruchomić. Taki APK
    // NIE nadaje się do dystrybucji w sklepie.
    val keystorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())
}

dependencies {
    implementation(project(":shared"))
}
