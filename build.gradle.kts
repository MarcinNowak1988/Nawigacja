// Android Gradle Plugin trafia na classpath tylko wtedy, gdy Android SDK jest dostępne.
//
// Nie da się tego zrobić przez blok `plugins { ... apply false }`, bo ten rozwiązuje
// artefakt pluginu niezależnie od `apply false`. Warunkowy `buildscript` pozwala budować
// i testować moduł `shared` bez pobierania SDK — patrz komentarz w settings.gradle.kts.
buildscript {
    val androidSdkAvailable: Boolean =
        (findProperty("reactivebike.android") as String?)?.toBooleanStrictOrNull()
            ?: (System.getenv("ANDROID_HOME") != null ||
                System.getenv("ANDROID_SDK_ROOT") != null ||
                File(rootDir, "local.properties").let { it.exists() && it.readText().contains("sdk.dir") })

    if (androidSdkAvailable) {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools.build:gradle:${property("agpVersion")}")
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Udostępnione podprojektom, żeby nie powielały wykrywania.
extra["androidSdkAvailable"] =
    (findProperty("reactivebike.android") as String?)?.toBooleanStrictOrNull()
        ?: (System.getenv("ANDROID_HOME") != null ||
            System.getenv("ANDROID_SDK_ROOT") != null ||
            File(rootDir, "local.properties").let { it.exists() && it.readText().contains("sdk.dir") })
