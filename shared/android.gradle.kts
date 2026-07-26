import com.android.build.gradle.LibraryExtension

// Skrypt ładowany wyłącznie wtedy, gdy Android SDK jest dostępne (patrz build.gradle.kts
// w tym katalogu). Odwołuje się do typów AGP, więc bez pluginu na classpath nie dałby się
// skompilować — dlatego jest osobnym plikiem, a nie częścią głównego skryptu modułu.
//
// Poziomy SDK czytane są z gradle.properties, bo akcesory katalogu wersji nie są dostępne
// w skryptach ładowanych przez `apply(from = ...)`.

extensions.configure<LibraryExtension>("android") {
    namespace = "pl.reactivebike.shared"
    compileSdk = property("androidCompileSdk").toString().toInt()

    defaultConfig {
        minSdk = property("androidMinSdk").toString().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
