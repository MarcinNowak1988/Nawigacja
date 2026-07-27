pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()

        // BRouter — silnik trasowania rowerowego działający na urządzeniu (ADR-0009).
        //
        // Projekt nie publikuje się do Maven Central; własne artefakty wystawia wyłącznie
        // do GitHub Packages, co wymagałoby tokenu przy każdym budowaniu. JitPack buduje
        // go wprost ze źródeł z GitHuba, więc jest jedyną drogą bez uwierzytelniania.
        maven("https://jitpack.io") {
            content {
                // Zawężamy do tej jednej grupy: JitPack ma odpowiadać za BRouter i nic
                // więcej, żeby literówka w innej zależności nie poszła po cichu tutaj.
                includeGroupByRegex("com\\.github\\.abrensch.*")
            }
        }
    }
}

rootProject.name = "reactivebike"

include(":shared")

// Warstwa androidowa dołącza się tylko wtedy, gdy Android SDK jest dostępne.
//
// Moduł `shared` jest z założenia platform-niezależny, więc jego kompilacja i testy nie
// powinny wymagać pobrania kilku gigabajtów SDK. Dzięki temu `./gradlew build` działa
// zarówno na maszynie deweloperskiej Androida, jak i na czystym runnerze CI liczącym
// wyłącznie logikę wspólną.
//
// Wykrywanie można nadpisać własnością `reactivebike.android=true|false`.
val androidSdkAvailable: Boolean =
    (extra.properties["reactivebike.android"] as String?)?.toBooleanStrictOrNull()
        ?: (System.getenv("ANDROID_HOME") != null ||
            System.getenv("ANDROID_SDK_ROOT") != null ||
            file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") })

if (androidSdkAvailable) {
    include(":androidApp")
} else {
    logger.lifecycle(
        "Android SDK nie wykryte - buduje wylacznie modul :shared. " +
            "Ustaw ANDROID_HOME albo sdk.dir w local.properties, zeby wlaczyc :androidApp.",
    )
}
