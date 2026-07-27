plugins {
    id("java-library")
}

/**
 * BRouter jako jeden moduł zbudowany z naszych reguł, a nie z ich.
 *
 * Dlaczego nie zależność z repozytorium: BRoutera nie ma w Maven Central, a własne artefakty
 * publikuje wyłącznie do swoich GitHub Packages, co wymagałoby tokenu przy każdym budowaniu.
 * JitPack, sprawdzony w CI, nie wystawia tych artefaktów. Zostaje podmoduł git — i przy okazji
 * jest to wariant najbardziej powtarzalny, bo nie zależy od żadnego pośrednika (ADR-0009).
 *
 * Dlaczego nie dołączamy ich buildu przez `includeBuild`: ich `buildSrc` wnosi checkstyle,
 * pmd i własne konwencje wersji, które stałyby się częścią naszego budowania i psuły je
 * z powodów niezwiązanych z tą aplikacją. Bierzemy więc same źródła.
 *
 * Pięć modułów trasowania scalamy w jeden, bo ich wzajemne zależności są kompletne i nie mają
 * niczego z zewnątrz — rozdzielanie ich u nas nie dałoby nic poza pracą. Świadomie pomijamy
 * `brouter-server` (warstwa HTTP), `brouter-map-creator` (budowanie segmentów z danych OSM)
 * i `brouter-routing-app` (ich własna aplikacja).
 */
val brouterModules = listOf(
    "brouter-core",
    "brouter-mapaccess",
    "brouter-util",
    "brouter-expressions",
    "brouter-codec",
)

sourceSets {
    named("main") {
        // Do źródeł BRoutera dokładamy własny katalog: mieszka w nim `BRouterHints`,
        // który sięga po pakietowo-prywatne pola podpowiedzi nawigacyjnych. Trzymamy go
        // po naszej stronie, żeby aktualizacja podmodułu go nie nadpisała.
        java.setSrcDirs(
            brouterModules.map { rootProject.file("third_party/brouter/$it/src/main/java") } +
                listOf(file("src/main/java")),
        )
        resources.setSrcDirs(brouterModules.map { rootProject.file("third_party/brouter/$it/src/main/resources") })
    }
    // Testy BRoutera zostają u nich. Nasze zadanie to dostarczyć bibliotekę, a ich zestaw
    // testowy ciągnie za sobą generowanie segmentów przez brouter-map-creator.
    named("test") {
        java.setSrcDirs(emptyList<String>())
        resources.setSrcDirs(emptyList<String>())
    }
}

java {
    // Ich `java-conventions` ustawia `options.release = 11`; my celujemy w 17, tak jak
    // reszta projektu, żeby bajtkod był jednolity.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    // Cudzy kod: ostrzeżenia kompilatora nie są naszym długiem i nie mają zaśmiecać logu.
    options.compilerArgs.add("-nowarn")
    options.isWarnings = false
}
