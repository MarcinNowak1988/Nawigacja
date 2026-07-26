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

Wydanie zawiera po jednym APK na architekturę oraz wariant uniwersalny. **`arm64-v8a`**
pasuje do praktycznie każdego telefonu z ostatnich lat i jest najmniejszy (~16 MB wobec
~52 MB wariantu uniwersalnego). Jeśli nie masz pewności co do architektury — weź
`universal`.

## Podpisywanie wydań

> **Bez tej konfiguracji kolejnych wydań nie da się instalować na wierzch poprzednich.**

Gdy sekrety podpisywania nie są ustawione, `assembleRelease` podpisuje APK kluczem
debugowym z `~/.android/debug.keystore`. Runner GitHub Actions to za każdym razem świeża
maszyna, a cache obejmuje `~/.gradle`, nie `~/.android` — więc **każde wydanie dostaje
inny klucz**. Android odmawia wtedy aktualizacji z błędem `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
i trzeba odinstalować aplikację przed każdą nową wersją.

Żeby to naprawić, wystarczy jednorazowo wygenerować klucz i wgrać go do sekretów:

```bash
keytool -genkeypair -v \
  -keystore reactivebike.keystore \
  -alias reactivebike \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=ReactiveBike, O=ReactiveBike, C=PL"

base64 -w0 reactivebike.keystore > reactivebike.keystore.b64
```

Następnie w repozytorium — *Settings → Secrets and variables → Actions* — dodaj cztery sekrety:

| Sekret | Wartość |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | zawartość pliku `reactivebike.keystore.b64` |
| `RELEASE_KEYSTORE_PASSWORD` | hasło podane przy `keytool` |
| `RELEASE_KEY_ALIAS` | `reactivebike` |
| `RELEASE_KEY_PASSWORD` | hasło klucza (przy powyższym poleceniu to samo co hasło keystore) |

**Pliku `.keystore` nigdy nie commituj** — `.gitignore` już go blokuje. Zgubienie tego
klucza oznacza, że kolejnych wydań nie da się zainstalować jako aktualizacji, więc
zrób jego kopię poza repozytorium.

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
