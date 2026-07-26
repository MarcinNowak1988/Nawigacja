# ADR-0001: Silnik trasowania jest kodem per-platforma, nie wspólnym

- **Status:** Zaakceptowany
- **Data:** 26 lipca 2026
- **Dotyczy:** sekcji 3 i 4 [dokumentacji technicznej](../DOKUMENTACJA_TECHNICZNA.md)

## Kontekst

Sekcja 4 specyfikacji umieszcza `Routing Engine – GraphHopper` wewnątrz bloku
„Wspólna logika: Kotlin Multiplatform", a sekcja 3 opisuje trasowanie jako element
warstwy współdzielonej między Androidem a iOS.

Jest to niewykonalne. **GraphHopper jest biblioteką Javy.** Na Androidzie działa
bez przeszkód, ale na iOS nie ma środowiska uruchomieniowego dla kodu JVM.

Jedyna istniejąca ścieżka to [`graphhopper/graphhopper-ios`](https://github.com/graphhopper/graphhopper-ios) —
port przez j2objc (translacja źródeł Javy na Objective-C). Jego README wprost ostrzega:
*„This is experimental so treat it accordingly"*, a wymagania to **JDK 8, Xcode 11.4–13
oraz iOS 11.0+**. Xcode 13 pochodzi z 2021 roku. Opieranie na tym produkcyjnej nawigacji
w 2026 roku oznaczałoby przyjęcie długu technicznego, którego nikt nie utrzymuje.

Sprawę komplikuje to, że wybór GraphHoppera nie jest przypadkowy. Sekcje 5 i 6 zakładają,
że moduł AI **w czasie działania aplikacji** podmienia wagi nawierzchni i infrastruktury.
GraphHopper ma do tego funkcję wprost: [custom models](https://docs.graphhopper.com/openapi/custom-model) —
reguły `priority` / `speed` z klauzulami `if` / `multiply_by` operujące na encoded values
takich jak `surface` czy `road_class`. To jest dokładnie mechanizm, którego wymaga sekcja 6.

## Decyzja

**Silnik trasowania przestaje być deklarowany jako kod wspólny.** Warstwa wspólna posiada
model kosztu **jako dane**; wiązanie z konkretnym silnikiem należy do warstwy per-platforma.

W praktyce:

- `commonMain` zawiera `RoutingWeights` i `EdgeCostCalculator` — czystą, przetestowaną
  reprezentację wzoru z sekcji 5.1 i semantyki wag z sekcji 5.2.
- Każda platforma tłumaczy `RoutingWeights` na format swojego silnika (dla GraphHoppera:
  na custom model).
- **Pierwszą wydawaną platformą jest Android**, z GraphHopperem i custom modelami.
- Silnik dla iOS pozostaje otwarty i zostanie rozstrzygnięty osobnym ADR-em, gdy warstwa
  androidowa się ustabilizuje.

## Konsekwencje

**Pozytywne**

- Dokumentacja przestaje obiecywać rzecz niewykonalną — zespół iOS nie trafi na tę
  ścianę dopiero w trakcie implementacji.
- Mechanizm nadpisywania wag z sekcji 6 dostajemy jako gotową funkcję GraphHoppera,
  bez pisania własnego modelu kosztu.
- Granica portu jest wąska i jawna: `RoutingWeights` plus interfejs silnika. Zmiana
  silnika po jednej stronie nie rusza logiki wspólnej ani UI.

**Negatywne**

- iOS nie dostanie trasowania offline w pierwszym wydaniu. Trzeba to jasno powiedzieć
  w planie produktowym, zamiast zakładać wydanie obu platform naraz.
- Istnieje ryzyko, że dwa różne silniki wyliczą dla tych samych danych nieco inne trasy.
  Wymusi to zestaw testów porównawczych na wspólnym zbiorze tras referencyjnych.
- Model kosztu żyje w dwóch miejscach: jako dane w `commonMain` i jako tłumaczenie
  na format silnika. Tłumaczenie wymaga własnych testów.

## Rozważane alternatywy

### Valhalla (C++) na obu platformach

[Valhalla](https://github.com/valhalla/valhalla) jest napisana w C++, ma kafelkową
strukturę grafu i była projektowana z myślą o urządzeniach o ograniczonej pamięci —
czyli o dokładnie takim zastosowaniu offline, jakiego wymaga sekcja 8.

Odrzucona z dwóch powodów. Po pierwsze, [nie publikuje oficjalnych artefaktów mobilnych](https://github.com/valhalla/valhalla/discussions/4509) —
trzeba samodzielnie zbudować `.so` przez NDK i `.xcframework`, a potem utrzymywać oba.
Po drugie, i ważniejsze: jej model kosztu **nie przyjmuje dowolnych mnożników per-nawierzchnia
podawanych w czasie działania**. Costing options są predefiniowane, więc realizacja sekcji 6
oznaczałaby napisanie własnego costing plugin w C++. To nieproporcjonalny koszt jak na etap,
na którym produkt nie ma jeszcze pierwszego wydania.

Valhalla pozostaje najpoważniejszym kandydatem dla iOS, gdy przyjdzie na to czas.

### Ferrostar

[Ferrostar](https://stadiamaps.com/products/routing-navigation/ferrostar-navigation-sdk/)
to dojrzały (wersja 0.51 w 2026 r.) SDK z rdzeniem w Ruście i bindingami przez UniFFI,
z natywnym UI dla Androida i iOS oraz integracją z MapLibre.

Odrzucona, bo rozwiązuje inny problem: Ferrostar jest SDK **nawigacji** — prowadzi po
wyznaczonej trasie, dopasowuje pozycję, generuje instrukcje. Trasy nie liczy, tylko
konsumuje odpowiedzi z Valhalli lub OSRM. Nie zastępuje więc silnika trasowania offline.
Jest natomiast realnym kandydatem na warstwę nawigacji w przyszłości.

### GraphHopper przez j2objc na iOS

Odrzucona wprost z powodów opisanych w Kontekście: projekt eksperymentalny, wymagający
JDK 8 i Xcode 13, bez gwarancji utrzymania.
