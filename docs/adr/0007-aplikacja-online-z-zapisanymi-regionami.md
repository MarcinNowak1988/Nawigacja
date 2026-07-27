# ADR-0007: Aplikacja online z zapisanymi regionami mapy

- **Status:** Zaakceptowany
- **Data:** 27 lipca 2026
- **Dotyczy:** sekcji 1, 2, 8 i 11 [dokumentacji technicznej](../DOKUMENTACJA_TECHNICZNA.md)
- **Zmienia założenia:** [ADR-0001](0001-silnik-trasowania-per-platforma.md), [ADR-0003](0003-offline-mbtiles.md), [ADR-0006](0006-mapy-offline-przez-offlinemanager.md)

## Kontekst

Specyfikacja stawia „pełny tryb offline" jako jeden z czterech wyróżników produktu
(sekcja 2) i opisuje w sekcji 8 zachowanie aplikacji w lesie i w górach, bez zasięgu.
Cała dotychczasowa architektura była temu podporządkowana: trasowanie miało działać
na urządzeniu, mapy miały pochodzić z plików na dysku, a moduł AI był jedynym elementem
wymagającym sieci.

Zderzenie z implementacją pokazało, ile ta obietnica naprawdę kosztuje, i to dwukrotnie.
Najpierw przy mapach: [ADR-0003](0003-offline-mbtiles.md) zakładał pliki `.mbtiles`,
ale okazało się, że nie ma skąd ich brać — trzeba by generować i hostować paczki,
co doprowadziło do [ADR-0006](0006-mapy-offline-przez-offlinemanager.md) i pobierania
kafelków przez sieć. Potem przy trasowaniu: silnik na urządzeniu wymaga grafu OSM,
zbudowanego poza telefonem i jakoś do niego dostarczonego. To ten sam problem, tylko
większy — graf dla jednego województwa to setki megabajtów, a jego przygotowanie
i wersjonowanie jest osobnym systemem, nie funkcją aplikacji.

Warto nazwać rzecz po imieniu: **obie te przeszkody to infrastruktura, nie kod.**
Port trasowania z [ADR-0001](0001-silnik-trasowania-per-platforma.md) jest gotowy
i ma działającą implementację. Brakuje wyłącznie danych i sposobu ich dostarczania.

## Decyzja

**ReactiveBike jest aplikacją online.** Wyznaczanie trasy wymaga połączenia z siecią.

Tryb offline zawęża się do **zapisanych regionów mapy**: użytkownik pobiera obszar,
który go interesuje, i ten obszar wyświetla się bez zasięgu wraz z pozycją, prędkością,
ciśnieniem i Storm Mode. Trasy w takim regionie nie wyznaczymy.

Konsekwencje dla wcześniejszych decyzji:

- **ADR-0001** pozostaje w mocy co do samego portu — `RouteEngine` dalej oddziela warstwę
  wspólną od silnika. Zmienia się to, że implementacją docelową jest silnik sieciowy,
  a nie przejściowym rozwiązaniem w drodze do silnika na urządzeniu.
- **ADR-0003** przestaje mieć zastosowanie w praktyce. Ustalenia o plikach `.mbtiles`
  zostają jako materiał na wypadek powrotu do dystrybucji gotowych paczek map.
- **ADR-0006** zyskuje na znaczeniu: `OfflineManager` przestaje być rozwiązaniem
  zastępczym i staje się **jedynym** mechanizmem trybu offline, więc jakość zapisywania
  regionów przestaje być drugorzędna.

## Konsekwencje

**Pozytywne**

- Znika największa niewiadoma projektu. Nie musimy budować ani utrzymywać procesu
  przygotowania grafu OSM, który przerastał skalę tej aplikacji.
- Trasowanie może korzystać z pełnej jakości silnika serwerowego — aktualne dane OSM,
  bez ograniczeń pamięci telefonu.
- Moduł AI z sekcji 6 przestaje być wyjątkiem w architekturze. Skoro trasowanie i tak
  wymaga sieci, model językowy przestaje być jedynym elementem, który psuje obietnicę
  offline.
- Zapisywanie regionów dostaje właściwą wagę i budżet uwagi.

**Negatywne**

- **Tracimy jeden z czterech filarów produktu z sekcji 2.** To nie jest korekta
  szczegółu — „pełny tryb offline" był tym, co miało odróżniać ReactiveBike od
  konkurencji. Pozostają trzy: brak funkcji społecznościowych, prywatność
  i adaptacja do warunków.
- **Prywatność słabnie.** Sekcja 9.1 wymieniała trasowanie lokalne jako gwarancję, że
  pozycja nie opuszcza urządzenia. Teraz punkt startowy i cel trafiają do zewnętrznego
  serwera przy każdym wyznaczeniu trasy. Wymaga to uczciwego opisania w sekcji 9.
- **Model wag z sekcji 5 działa tylko częściowo.** Silnik serwerowy przyjmuje kilka
  parametrów profilu rowerowego zamiast dowolnych mnożników per nawierzchnia.
  Aplikacja dalej liczy pełny model i pokazuje go użytkownikowi, ale router go nie widzi.
- W lesie bez zasięgu aplikacja przestaje być nawigacją, a zostaje mapą z komputerem
  rowerowym. Trzeba to powiedzieć wprost użytkownikowi, a nie liczyć, że nie zauważy.

## Rozważane alternatywy

### Utrzymanie kursu na trasowanie offline

Wierne specyfikacji i zachowujące wszystkie cztery filary. Odrzucone ze względu na koszt
infrastruktury: przygotowanie, hostowanie i wersjonowanie grafów OSM dla obsługiwanych
regionów to praca ciągła, nie jednorazowa, i przekracza skalę projektu.

Decyzja jest odwracalna. Port `RouteEngine` pozostaje granicą, za którą można później
wstawić silnik lokalny — wtedy ten ADR zostanie zastąpiony nowszym.

### Tryb hybrydowy: trasowanie sieciowe z lokalnym zapasowym

Kuszące, bo daje jedno i drugie. Odrzucone, bo wymaga zbudowania całej infrastruktury
offline mimo wszystko, a do tego utrzymania dwóch silników dających różne trasy dla tych
samych danych. Koszt rośnie, a nie maleje.

### Buforowanie wyznaczonych tras na później

Pozwala przejechać ponownie trasę wyznaczoną w zasięgu. Warte rozważenia jako osobna
funkcja, ale nie zastępuje trasowania offline: nie wyznaczy nowej trasy po zjechaniu
z zapisanej, czyli zawodzi dokładnie wtedy, gdy nawigacja jest potrzebna.
