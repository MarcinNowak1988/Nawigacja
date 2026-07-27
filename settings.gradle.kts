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

// Silnik trasowania BRouter budowany ze źródeł z podmodułu `third_party/brouter` (ADR-0009).
// To zwykła biblioteka Javy, więc kompiluje się bez Android SDK — także tutaj, lokalnie.
if (file("third_party/brouter/brouter-core/src/main/java").exists()) {
    include(":brouter")
} else {
    logger.lifecycle(
        "Podmodul BRouter nie jest pobrany - pomijam modul :brouter. " +
            "Uruchom: git submodule update --init --depth 1",
    )
}

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
