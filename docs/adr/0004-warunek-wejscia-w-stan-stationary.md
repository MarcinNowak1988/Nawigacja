# ADR-0004: Wejście w stan STATIONARY wymaga potwierdzenia z akcelerometru

- **Status:** Zaakceptowany
- **Data:** 26 lipca 2026
- **Dotyczy:** sekcji 7 [dokumentacji technicznej](../DOKUMENTACJA_TECHNICZNA.md)

## Kontekst

Sekcja 7 definiuje stan `STATIONARY` warunkiem „Postój — prędkość < 3 km/h", a wyjście
z niego przez wybudzenie akcelerometrem. W tym stanie GPS jest **całkowicie wyłączony**.

Warunek oparty wyłącznie na prędkości ma lukę. Rowerzysta na stromym podjeździe potrafi
zejść poniżej 3 km/h, cały czas jadąc — na podjazdach o dużym nachyleniu jest to sytuacja
zwyczajna, nie brzegowa. Przy dosłownym odczytaniu specyfikacji aplikacja wyłączyłaby
wtedy GPS w środku jazdy.

Zadziałałby wprawdzie akcelerometr i wybudził system, ale skutkiem byłoby oscylowanie
między `CRUISE` a `STATIONARY` przez cały podjazd: GPS gaszony i zapalany co kilka sekund.
To gorsze dla baterii niż stabilne `CRUISE`, a do tego degraduje dokładność pozycji
dokładnie tam, gdzie nawigacja bywa potrzebna — na rozwidleniach leśnych dróg pod górę.

Intencja specyfikacji jest przy tym czytelna. Tabela mówi o „przerwach w jeździe",
a nie o wolnej jeździe.

## Decyzja

Wejście w `STATIONARY` wymaga **dwóch warunków jednocześnie**:

1. prędkość poniżej `STATIONARY_SPEED_KMH` (3 km/h), oraz
2. **brak ruchu wykrywanego przez akcelerometr**.

Akcelerometr jest już wymagany przez specyfikację do wybudzania z tego stanu, więc
decyzja nie wprowadza nowej zależności sprzętowej — wykorzystuje czujnik, który i tak
musi być odpytywany.

Przy okazji ustalamy **pierwszeństwo reguł**, o którym diagram w sekcji 7 milczy,
a które jest potrzebne, gdy kilka warunków zachodzi naraz:

1. zbliżający się manewr → `CRITICAL` (bezpieczeństwo nawigacji przed oszczędnością baterii),
2. postój → `STATIONARY`,
3. stan ekranu rozstrzyga między `CRUISE` a `SLEEP`.

Ze stanu `CRITICAL` zachowujemy jedyne wyjście przewidziane diagramem — do `CRUISE`
po wykonaniu manewru. Dopóki manewr jest przed nami, precyzja pozycji wygrywa z oszczędzaniem.

## Konsekwencje

**Pozytywne**

- Znika oscylacja `CRUISE` ↔ `STATIONARY` na wolnych podjazdach.
- Warunek jest bliższy deklarowanej intencji („przerwa w jeździe") niż sam próg prędkości.
- Pierwszeństwo reguł jest zapisane i przetestowane, więc nie zostanie odtworzone
  przypadkowo i różnie na każdej platformie.

**Negatywne**

- Wejście w `STATIONARY` może się opóźnić o czas potrzebny akcelerometrowi na
  potwierdzenie bezruchu — kosztem jest kilka dodatkowych odczytów GPS na każdym postoju.
- Rower stojący na wietrze albo oparty o drgające podłoże może generować fałszywy ruch
  i nie pozwolić wejść w `STATIONARY`. Wymaga to progu czułości po stronie implementacji
  natywnej; wartość progu nie jest przedmiotem tego ADR-a.

## Rozważane alternatywy

### Dosłowne trzymanie się specyfikacji

Odrzucona: pozostawia znany defekt w kodzie, który właśnie pokrywamy testami. Zapisanie
odstępstwa w ADR-ze jest tańsze niż diagnozowanie później zgłoszeń o „gasnącym GPS-ie pod górę".

### Obniżenie progu prędkości poniżej 3 km/h

Przesuwa problem, zamiast go usuwać — próg wciąż istnieje i wciąż da się pod niego zjechać
na dostatecznie stromym podjeździe. Dodatkowo opóźnia wejście w `STATIONARY` przy realnych
postojach, czyli pogarsza to, po co ten stan istnieje.

### Histereza czasowa na prędkości

Wejście w `STATIONARY` dopiero po N sekundach poniżej progu. Rozwiązuje oscylację, ale
wprowadza do maszyny stanów zależność od czasu, przez co przestaje być czystą funkcją
przejścia i staje się znacznie trudniejsza do przetestowania. Sygnał z akcelerometru daje
ten sam efekt bez wprowadzania zegara.
