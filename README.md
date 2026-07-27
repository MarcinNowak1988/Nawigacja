# ReactiveBike

[![Build](https://github.com/MarcinNowak1988/Nawigacja/actions/workflows/build.yml/badge.svg)](https://github.com/MarcinNowak1988/Nawigacja/actions/workflows/build.yml)

Nawigacja rowerowa **działająca online**, z możliwością zapisania regionu mapy
do użytku bez zasięgu.

Trzy filary produktu: brak funkcji społecznościowych, prywatność oraz adaptacja trasy
do warunków pogodowych. Czwarty — pełny tryb offline — został wycofany; **wyznaczanie
trasy wymaga sieci**, a bez zasięgu zostaje mapa z zapisanego regionu wraz z pozycją,
prędkością, ciśnieniem i Storm Mode. Powody i koszty tej decyzji opisuje
[ADR-0007](docs/adr/0007-aplikacja-online-z-zapisanymi-regionami.md).

> **Status: alfa.** Aplikacja androidowa jeździ: mapa, pozycja, ślad przejazdu,
> nawigacja z punktu A do B z punktami pośrednimi wskazywanymi na mapie albo przez
> wyszukiwanie tekstowe, prowadzenie w tle przy wygaszonym ekranie, dane postępu,
> wybór rodzaju roweru, zapowiedzi
> manewrów głosem, pogoda i zapisywanie regionów mapy.
> Modułu iOS ani modułu AI z sekcji 6 jeszcze nie ma.

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
androidApp/          Aplikacja Android — mapa, trasowanie, nawigacja głosowa
```

Moduł `shared` zawiera dziś logikę biznesową w całości niezależną od platformy:

**`routing`**

- **model kosztu krawędzi** — wzór z sekcji 5.1 specyfikacji wraz z semantyką wag
  i normalizacją do postaci wyłącznie podwyższającej,
- **port `RouteEngine`** — granica, przez którą warstwa natywna wstrzykuje swój silnik
  trasowania ([ADR-0001](docs/adr/0001-silnik-trasowania-per-platforma.md)); dziś stoi
  za nią klient Valhalli,
- **postęp na trasie i zapowiedzi manewrów** — dystans do najbliższego manewru, progi
  zapowiedzi i wykrywanie zjechania z trasy,
- **plan przejazdu** — start, punkty pośrednie i cel, wraz z konsumowaniem punktów już
  minietych, żeby przeliczenie trasy prowadziło do przodu, a nie zawracało,
- **profile rowerowe** — miejski, trekkingowy, górski i szosowy; pogoda może zaostrzyć
  wymagania profilu, ale nigdy ich nie rozluźnia,
- **dane postępu** — ile trasy za nami, ile zostało i szacowany czas dojazdu liczony
  z tempa rowerzysty, a na postoju z planu silnika,
- **etapy przejazdu i wykrywanie dojazdu** — układanie trasy jest oddzielone od jazdy nią,
  więc zapowiedzi, przeliczanie trasy i najkosztowniejszy tryb GPS włączają się dopiero
  po rozpoczęciu nawigacji.

**`geocoding`**

- **port `Geocoder`** — granica dostawcy wyszukiwania, ta sama co `RouteEngine` dla trasowania,
- **klienci Mapy.com i Nominatim** — budowanie zapytania wraz z kodowaniem adresu
  i parsowaniem odpowiedzi. Mapy.com wymagają klucza API; bez niego aplikacja wraca
  do Nominatima ([ADR-0008](docs/adr/0008-wyszukiwanie-miejsc-w-mapy-com.md)).

**`maps`**

- **szacowanie kosztu pobrania regionu** — liczba kafelków i przybliżony rozmiar,
  liczone **zanim** pobieranie ruszy.

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

Interfejs powstaje w kodzie, bez Compose — docelowe UI z sekcji 3 specyfikacji
to osobny krok.

APK powstaje w [workflow `APK`](.github/workflows/release-apk.yml):

- **na żądanie** — zakładka Actions → *APK* → *Run workflow*; plik ląduje jako artefakt przebiegu,
- **na tagu `v*`** — dodatkowo powstaje wydanie GitHub z APK w załącznikach.

```bash
git tag v0.14.0 && git push origin v0.14.0
```

Wydanie zawiera po jednym APK na architekturę oraz wariant uniwersalny. **`arm64-v8a`**
pasuje do praktycznie każdego telefonu z ostatnich lat i jest najmniejszy (~16 MB wobec
~52 MB wariantu uniwersalnego). Jeśli nie masz pewności co do architektury — weź
`universal`.

## Podpisywanie wydań

**Wydania instalują się na wierzch poprzednich.** Odpowiada za to plik
`androidApp/debug.keystore`, który jest **celowo w repozytorium**.

Powód: bez niego `assembleRelease` sięgał po klucz debugowy z `~/.android/debug.keystore`,
a runner GitHub Actions to za każdym razem świeża maszyna — cache obejmuje `~/.gradle`,
nie `~/.android`. Każde wydanie dostawało więc **inny klucz**, Android odmawiał aktualizacji
z błędem `INSTALL_FAILED_UPDATE_INCOMPATIBLE` i trzeba było odinstalowywać aplikację przed
każdą nową wersją. Stały plik to kończy.

Trzy rzeczy warto wiedzieć wprost:

- **Klucz debugowy nie jest sekretem** i nie udaje nim być. Hasło to `android`, alias
  `androiddebugkey` — tak jak w każdym domyślnym kluczu debugowym Androida.
- Ponieważ repozytorium jest publiczne, **ktokolwiek może zbudować APK podpisany tym samym
  kluczem**. Przy dystrybucji przez własne wydania GitHuba to akceptowalne; przy szerszej
  dystrybucji już nie.
- **Do sklepu taki APK się nie nadaje.** Do tego służą sekrety podpisywania poniżej — gdy
  są ustawione, wygrywają z kluczem debugowym.

### Własny klucz wydania (opcjonalnie, wymagane do sklepu)

Żeby podpisywać kluczem, którego nie ma w repozytorium, wygeneruj go i wgraj do sekretów:

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

**Tego pliku `.keystore` nie commituj** — `.gitignore` blokuje `*.keystore`, z jawnym
wyjątkiem dla `androidApp/debug.keystore`. Zgubienie klucza wydania oznacza, że kolejnych
wydań nie da się zainstalować jako aktualizacji, więc zrób jego kopię poza repozytorium.

## Stos technologiczny

| Warstwa | Technologia |
|---|---|
| Logika współdzielona | Kotlin Multiplatform |
| UI — Android | Jetpack Compose |
| UI — iOS | SwiftUI |
| Silnik mapy | MapLibre GL Native; regiony offline przez `OfflineManager` ([ADR-0006](docs/adr/0006-mapy-offline-przez-offlinemanager.md)) |
| Silnik trasowania | Valhalla przez sieć, za portem `RouteEngine` ([ADR-0001](docs/adr/0001-silnik-trasowania-per-platforma.md), [ADR-0007](docs/adr/0007-aplikacja-online-z-zapisanymi-regionami.md)) |
| Warstwa sieciowa | Ktor |
| Baza danych | SQLDelight |

## Dalsze kroki

Lista otwartych kwestii znajduje się w [sekcji 11 dokumentacji technicznej](docs/DOKUMENTACJA_TECHNICZNA.md#11-otwarte-kwestie-i-dalsze-kroki).
Najbliżej są: zarządzanie zapisanymi regionami (nazwy, lista, usuwanie pojedynczo),
usługa pierwszoplanowa — żeby oszczędzanie baterii z sekcji 7 działało przy zgaszonym
ekranie — oraz rozstrzygnięcie, czy korzystać z publicznej instancji trasowania,
czy własnej.
