plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())

    // Na razie wyłącznie target `jvm` — dzięki temu `./gradlew build` przechodzi na Linuksie i w CI.
    //
    // `androidTarget()` oraz targety iOS (`iosArm64`, `iosSimulatorArm64`) dochodzą razem
    // z modułami aplikacji, na maszynie z Android SDK i Xcode. To zmiana addytywna:
    // cały kod w `commonMain` jest platform-niezależny i nie wymaga wtedy modyfikacji.
    // Szczegóły podziału na warstwę wspólną i warstwę per-platforma: docs/adr/0001-silnik-trasowania-per-platforma.md
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
