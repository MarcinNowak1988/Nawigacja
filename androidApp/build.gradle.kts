import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
        versionCode = 14
        versionName = "0.14.0"
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

        // Klucz debugowy z repozytorium zamiast tego z `~/.android`.
        //
        // Runner GitHub Actions to za kazdym razem swieza maszyna, a cache obejmuje
        // `~/.gradle`, nie `~/.android` — wiec domyslny klucz debugowy byl generowany od nowa
        // przy kazdym wydaniu. Android widzial inny podpis i odmawial aktualizacji, zmuszajac
        // do odinstalowania aplikacji przed kazda nowa wersja. Staly plik to konczy.
        //
        // Klucz debugowy nie jest sekretem i nie udaje nim byc — sluzy wylacznie temu, zeby
        // kolejne wydania mialy ten sam podpis.
        getByName("debug") {
            val shared = rootProject.file("androidApp/debug.keystore")
            if (shared.exists()) {
                storeFile = shared
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
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

    // Biblioteki natywne MapLibre to ok. 46 MB na cztery architektury, przy 8 MB reszty
    // aplikacji. Każde urządzenie potrzebuje dokładnie jednej, więc budujemy osobny APK
    // per architektura — arm64-v8a schodzi wtedy do ok. 20 MB zamiast 51 MB.
    //
    // Wariant uniwersalny zostaje celowo: APK rozprowadzamy przez wydania GitHuba, gdzie
    // pobierający nie powinien musieć wiedzieć, jaką architekturę ma jego telefon.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
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

    // Kompilujemy toolchainem JDK 21, ale bajtkod celuje w Javę 17 — tak samo jak target
    // Android w module `shared`. Bez tego Kotlin poszedłby za toolchainem (21), a javac za
    // `compileOptions` (17), co Gradle odrzuca jako niespójność JVM-target.
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.maplibre.android.sdk)
    implementation(libs.kotlinx.coroutines.android)
}
