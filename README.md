# ReactiveBike

[![Build](https://github.com/MarcinNowak1988/Nawigacja/actions/workflows/build.yml/badge.svg)](https://github.com/MarcinNowak1988/Nawigacja/actions/workflows/build.yml)

Nawigacja rowerowa w modelu **offline-first**, projektowana z założeniem, że w lesie
i w górach aplikacja ma działać tak samo dobrze jak w mieście z pełnym zasięgiem.

Cztery filary produktu: brak funkcji społecznościowych, prywatność, pełny tryb offline
oraz adaptacja trasy do warunków pogodowych.

> **Status: pre-alfa.** Repozytorium zawiera specyfikację techniczną, decyzje
> architektoniczne oraz przetestowany rdzeń logiki biznesowej. Aplikacje mobilne
> jeszcze nie powstały.

## Dokumentacja

| Dokument | Zawartość |
|---|---|
| [Dokumentacja techniczna](docs/DOKUMENTACJA_TECHNICZNA.md) | Architektura, logika trasowania, moduł AI, zarządzanie baterią, tryb offline, prywatność |
| [Decyzje architektoniczne (ADR)](docs/adr/README.md) | Dlaczego system wygląda tak, a nie inaczej |

## Układ repozytorium

```
docs/                Specyfikacja techniczna i ADR-y
shared/              Moduł Kotlin Multiplatform — wspólna logika biznesowa
  src/commonMain/    Kod platform-niezależny
  src/commonTest/    Testy uruchamiane na każdym targecie
androidApp/          Aplikacja Android (ekran diagnostyczny, nie interfejs nawigacji)
```

Moduł `shared` zawiera dziś logikę biznesową w całości niezależną od platformy:

**`routing`**

- **model kosztu krawędzi** — wzór z sekcji 5.1 specyfikacji wraz z semantyką wag
  i normalizacją do postaci wyłącznie podwyższającej,
- **port `RouteEngine`** — granica, przez którą warstwa natywna wstrzykuje swój silnik
  trasowania ([ADR-0001](docs/adr/0001-silnik-trasowania-per-platforma.md)).

**`weather`**

- **kontrakt i walidator odpowiedzi modułu AI** — ścisła walidacja z fallbackiem na wagi
  domyślne, żeby błąd modelu nie przerywał nawigacji,
- **polityka bufora pogodowego** — ważność liczona od momentu wydania prognozy,
- **detektor burzy** — spadek ciśnienia z barometru, nasłuchiwany równolegle z buforem
  ([ADR-0005](docs/adr/0005-barometr-nasluchiwany-rownolegle.md)).

**`gps`**

- **maszyna stanów GPS** — czysta funkcja przejścia sterująca częstotliwością odpytywania GPS.

## Budowanie i testy

Wymagany JDK 21. Gradle dostarcza wrapper, więc nie trzeba instalować go osobno.

```bash
./gradlew build           # kompilacja i testy
./gradlew :shared:jvmTest # same testy modułu shared
```

**Warstwa androidowa jest opcjonalna.** Moduł `androidApp` i target `android` w `shared`
włączają się automatycznie, gdy wykryte zostanie Android SDK — po zmiennej `ANDROID_HOME`,
`ANDROID_SDK_ROOT` albo wpisie `sdk.dir` w `local.properties`. Bez SDK budowany jest
wyłącznie `shared` z targetem `jvm`, dzięki czemu logikę biznesową da się kompilować
i testować bez pobierania kilku gigabajtów SDK. Wykrywanie można nadpisać:

```bash
./gradlew build -Preactivebike.android=false   # wymuś build bez warstwy androidowej
```

Targety iOS dojdą razem z modułem iOS — Kotlin/Native kompiluje je wyłącznie na macOS
z Xcode. Kod w `commonMain` nie będzie wtedy wymagał zmian.

## APK

Aplikacja androidowa to na razie **ekran diagnostyczny**, nie interfejs nawigacji: pokazuje,
że logika z modułu `shared` działa na urządzeniu. Mapy, trasowania ani UI z sekcji 3
specyfikacji jeszcze nie ma.

APK powstaje w [workflow `APK`](.github/workflows/release-apk.yml):

- **na żądanie** — zakładka Actions → *APK* → *Run workflow*; plik ląduje jako artefakt przebiegu,
- **na tagu `v*`** — dodatkowo powstaje wydanie GitHub z APK w załącznikach.

```bash
git tag v0.1.0 && git push origin v0.1.0
```

Bez skonfigurowanego klucza APK jest podpisany **kluczem debugowym** — instaluje się
i nadaje do testów, ale nie do dystrybucji w sklepie. Żeby podpisywać kluczem wydania,
ustaw sekrety repozytorium: `RELEASE_KEYSTORE_BASE64` (keystore zakodowany base64),
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.

## Stos technologiczny

| Warstwa | Technologia |
|---|---|
| Logika współdzielona | Kotlin Multiplatform |
| UI — Android | Jetpack Compose |
| UI — iOS | SwiftUI |
| Silnik mapy | MapLibre GL Native (`.mbtiles` offline) |
| Silnik trasowania | GraphHopper — warstwa per-platforma, patrz [ADR-0001](docs/adr/0001-silnik-trasowania-per-platforma.md) |
| Warstwa sieciowa | Ktor |
| Baza danych | SQLDelight |

## Dalsze kroki

Lista otwartych kwestii znajduje się w [sekcji 11 dokumentacji technicznej](docs/DOKUMENTACJA_TECHNICZNA.md#11-otwarte-kwestie-i-dalsze-kroki).
Najbliższy krok to moduły aplikacji Android wraz z wiązaniem GraphHoppera i MapLibre —
wymaga maszyny z Android SDK.
