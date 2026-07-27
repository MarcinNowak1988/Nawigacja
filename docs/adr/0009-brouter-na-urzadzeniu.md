# ADR-0009: Trasowanie rowerowe przez BRouter na urządzeniu

- **Status:** Zaakceptowany, wdrożenie w toku
- **Data:** 27 lipca 2026
- **Dotyczy:** sekcji 3, 5 i 8 [dokumentacji technicznej](../DOKUMENTACJA_TECHNICZNA.md)
- **Zmienia założenia:** [ADR-0001](0001-silnik-trasowania-per-platforma.md), [ADR-0007](0007-aplikacja-online-z-zapisanymi-regionami.md)

## Kontekst

Trasy liczy dziś publiczna instancja Valhalli. Jest darmowa i bez klucza, ale dla roweru bywa
zawodna — potrafi zjechać z drogi rowerowej na ruchliwą szosę. Szukaliśmy czegoś, co równie
dobrze wytycza trasy rowerowe i nadal nic nie kosztuje.

Przegląd darmowych możliwości dał trzy realne:

| Rozwiązanie | Klucz | Ograniczenia | Jakość dla roweru |
|---|---|---|---|
| Valhalla FOSSGIS (obecne) | nie | brak | przyzwoita |
| openrouteservice | tak, darmowy | 2500 zapytań/dobę | dobra |
| **BRouter** | nie | brak | najlepsza w ekosystemie OSM |

BRouter **nie udostępnia publicznego API** — mówią to wprost. Istnieją publiczne instancje
webowe, ale to prywatne serwery pod klienta przeglądarkowego; wołanie ich z aplikacji byłoby
pasożytowaniem bez zgody. Za to BRouter jest biblioteką **działającą na urządzeniu**, a dane
trasowania rozprowadza jako pliki `.rd5` w kaflach 5°×5°, przebudowywane co tydzień.

To jest zwrot wobec [ADR-0007](0007-aplikacja-online-z-zapisanymi-regionami.md). Odrzuciliśmy
tam trasowanie offline, bo przygotowanie i hostowanie grafu OSM „przerasta skalę tej
aplikacji". **Ta przesłanka okazała się nieaktualna: taka infrastruktura już istnieje
i ktoś ją utrzymuje.**

## Decyzja

**Trasowanie rowerowe liczy BRouter na urządzeniu**, za istniejącym portem `RouteEngine`
z [ADR-0001](0001-silnik-trasowania-per-platforma.md).

Ustalenia, które zebraliśmy przed decyzją:

- **Licencja MIT** — bez konsekwencji copyleft dla naszego kodu.
- **Nie ma go w Maven Central.** Projekt publikuje artefakty wyłącznie do własnych GitHub
  Packages, co wymagałoby tokenu przy każdym budowaniu. Dlatego sięgamy po **JitPack**, który
  buduje go wprost ze źródeł. Repozytorium JitPack jest w konfiguracji zawężone do grupy
  `com.github.abrensch*`, żeby literówka w innej zależności nie poszła po cichu tą drogą.
- **Nazwy segmentów** wynikają z `NodesCache.fileForSegment`: `E{lon}_N{lat}.rd5`, gdzie
  współrzędne to lewy dolny róg kafla zaokrąglony w dół do wielokrotności 5°, ze znakami
  `W`/`S` dla wartości ujemnych.

## Konsekwencje

**Pozytywne**

- Trasy rowerowe liczone przez silnik pisany pod rower, z konfigurowalnymi profilami.
- Bez klucza, bez limitów dobowych, bez zależności od cudzego serwera.
- **Wraca trasowanie bez zasięgu** — filar produktu wycofany w ADR-0007.
- Zapytania o trasę przestają opuszczać urządzenie, co odwraca stratę prywatności
  odnotowaną w sekcji 9.1.

**Negatywne**

- **BRouter jest Javą, więc działa na Androidzie, nie na iOS** — dokładnie ten sam problem,
  przez który ADR-0001 odrzucił GraphHoppera. Skoro najpierw wydajemy Androida, dziś kosztuje
  to niewiele, ale iOS będzie wymagał osobnego rozwiązania.
- **Użytkownik musi pobrać pliki segmentów.** To kilkadziesiąt megabajtów na kafel; obszar
  Polski to sześć kafli. Dochodzi więc zarządzanie plikami, ich aktualizacją i miejscem
  na dysku — praca porównywalna z zapisywaniem regionów mapy.
- **Zależność budowana przez JitPack** to usługa pośrednicząca, której dostępność jest
  kolejnym punktem awarii buildu. Wariant zapasowy: BRouter jako podmoduł git.
- **Dostępności artefaktu nie dało się potwierdzić przed wdrożeniem.** Środowisko, w którym
  powstaje ten kod, nie ma dostępu do JitPack, więc pierwszy commit z zależnością jest
  **świadomym eksperymentem rozstrzyganym przez CI**, a nie krokiem pewnym.

## Rozważane alternatywy

### openrouteservice

Darmowy klucz, 2500 zapytań na dobę, profile rowerowe wyraźnie lepsze od obecnych. Jedna
runda pracy zamiast kilku. Odrzucone, bo zostawia trasowanie sieciowym i nie odzyskuje trybu
offline — a klucz w publicznym repozytorium to problem, który dopiero co odrzuciliśmy
przy Mapy.com.

### Wołanie publicznych instancji BRoutera

Odrzucone jednoznacznie: projekt nie udostępnia publicznego API, więc byłoby to obciążanie
cudzego serwera bez zgody.

### Wiązanie z aplikacją BRouter zainstalowaną osobno

BRouter ma własną aplikację z usługą, z której korzystają OsmAnd i Locus Map. Zdejmuje
z nas zarządzanie segmentami i integrację biblioteki. Odrzucone jako rozwiązanie podstawowe,
bo wymaga od użytkownika instalacji drugiej aplikacji — ale to najtańszy wariant zapasowy,
gdyby integracja biblioteki okazała się niewykonalna.
