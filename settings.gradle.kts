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
