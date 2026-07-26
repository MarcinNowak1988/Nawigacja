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
./gradlew build          # kompilacja i testy
./gradlew :shared:jvmTest # same testy modułu shared
```

Moduł `shared` deklaruje na razie wyłącznie target `jvm`, dzięki czemu build przechodzi
na dowolnej maszynie i w CI — bez Android SDK i bez Xcode. Targety `android` oraz iOS
dojdą razem z modułami aplikacji; kod w `commonMain` nie będzie wtedy wymagał zmian.

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
