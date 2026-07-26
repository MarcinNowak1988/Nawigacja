# ADR-0003: Mapy offline na plikach `.mbtiles`

- **Status:** Zaakceptowany
- **Data:** 26 lipca 2026
- **Dotyczy:** sekcji 3, 8 i 11 [dokumentacji technicznej](../DOKUMENTACJA_TECHNICZNA.md)

## Kontekst

Sekcja 3 przypisuje MapLibre GL Native obsługę plików `.mbtiles` offline, a sekcja 8
opiera na nich pierwszy stopień degradacji przy utracie zasięgu. Założenie jest słuszne,
ale specyfikacja nie wspomina o ograniczeniach, które wpływają na projekt pobierania map —
wymieniony w sekcji 11 jako otwarta kwestia.

Weryfikacja ([dyskusja #971](https://github.com/maplibre/maplibre-native/discussions/971)
w repozytorium MapLibre Native) potwierdza obsługę schematu `mbtiles://` w definicji stylu,
zarówno dla kafelków wektorowych, jak i rastrowych, na Androidzie i iOS. Ujawnia jednak
dwa ograniczenia:

1. **Jedno źródło obsługuje dokładnie jeden plik.** Nie ma mechanizmu, który przy braku
   kafelka w jednym pliku sięgnąłby do kolejnego.
2. **Na Androidzie nie można wskazać pliku w `assets/`.** Trzeba go najpierw skopiować
   do pamięci wewnętrznej i użyć ścieżki absolutnej: `mbtiles:///absolutna/sciezka/plik.mbtiles`.

Pierwsze ograniczenie jest istotne, bo naturalny scenariusz użycia to pobranie kilku
sąsiadujących regionów — a rowerzysta regularnie przekracza ich granice.

## Decyzja

Pozostajemy przy `.mbtiles` i MapLibre Native. Dodatkowo ustalamy:

- **Region pobrania odpowiada dokładnie jednemu plikowi `.mbtiles` i jednemu źródłu w stylu.**
- Obsługa wielu regionów naraz jest realizowana przez **wiele źródeł w stylu mapy**,
  deklarowanych dynamicznie na podstawie listy pobranych regionów. Alternatywę — scalanie
  plików po stronie urządzenia — odrzucamy: kosztuje czas i podwaja zajętość dysku.
- Regiony projektujemy **z zakładką na granicach**, żeby przy ich przekraczaniu nie
  pojawiała się luka w kafelkach.
- Na Androidzie plik `.mbtiles` jest kopiowany do pamięci wewnętrznej przy pierwszym
  użyciu; w stylu trafia wyłącznie ścieżka absolutna.

## Konsekwencje

**Pozytywne**

- Ograniczenia są znane przed napisaniem warstwy pobierania map, a nie po.
- Podział „jeden region = jeden plik" upraszcza zarządzanie: aktualizacja albo usunięcie
  regionu to operacja na jednym pliku.

**Negatywne**

- Styl mapy przestaje być statycznym zasobem — musi być składany w czasie działania
  z listy pobranych regionów.
- Kopiowanie z `assets/` na Androidzie oznacza chwilowo podwójną zajętość dysku i wymaga
  obsłużenia przerwania w trakcie kopiowania.
- Zakładki na granicach regionów to nadmiarowe kafelki, czyli większe pobrania.

## Rozważane alternatywy

### Scalanie pobranych regionów w jeden plik `.mbtiles`

Rozwiązuje problem jednego źródła na plik i upraszcza styl. Odrzucona ze względu na koszt
po stronie urządzenia: scalanie wymaga przepisania bazy SQLite, czyli czasu i miejsca
na drugą kopię danych — a operacja powtarza się przy każdej zmianie zestawu regionów.

### Format PMTiles

Nowocześniejszy format przystosowany do zapytań zakresowych. Odrzucona na tym etapie,
bo specyfikacja jednoznacznie wskazuje `.mbtiles`, a zmiana formatu pociągnęłaby za sobą
przebudowę całego procesu przygotowania danych. Warta rozważenia osobnym ADR-em, jeśli
zarządzanie wieloma źródłami okaże się uciążliwe.
