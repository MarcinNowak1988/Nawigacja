# ADR-0005: Barometr nasłuchiwany równolegle z buforem pogodowym

- **Status:** Zaakceptowany
- **Data:** 26 lipca 2026
- **Dotyczy:** sekcji 8 [dokumentacji technicznej](../DOKUMENTACJA_TECHNICZNA.md)

## Kontekst

Diagram degradacji w pierwszej wersji sekcji 8 układał zachowanie offline w sekwencję:
mapy zbuforowane → prognoza z bufora → *dopiero po jej wygaśnięciu* nasłuch barometru →
Storm Mode. Nasłuch barometru startował więc dopiero po przeterminowaniu prognozy.

Powstaje z tego okno, w którym aplikacja jest ślepa dokładnie na to, przed czym Storm Mode
ma chronić. Rowerzysta traci zasięg, prognoza jest jeszcze ważna, a czterdzieści minut
później nadciąga front — barometr tego nie zauważy, bo nikt go jeszcze nie słucha.
System obudzi się dopiero wtedy, gdy prognoza wygaśnie, czyli potencjalnie z półtoragodzinnym
opóźnieniem względem chwili, w której ciśnienie zaczęło spadać.

Warto zauważyć asymetrię tych dwóch źródeł. Prognoza z bufora to dane **z przeszłości**,
opisujące przewidywania sprzed godzin. Barometr to **pomiar bieżący**, wykonywany tu i teraz.
Sekwencja z pierwszej wersji dawała pierwszeństwo danym starszym.

Sam odczyt barometru nie wymaga sieci, jest tani energetycznie i nie koliduje z niczym,
co dzieje się w trybie offline.

## Decyzja

**Nasłuch barometru startuje z chwilą utraty zasięgu i biegnie równolegle z korzystaniem
z bufora pogodowego**, a nie po jego wygaśnięciu.

Przy rozstrzyganiu, które wagi obowiązują, obowiązuje pierwszeństwo:

1. **burza wykryta z barometru** — bieżący pomiar bije prognozę sprzed godzin,
2. **ważny bufor pogodowy**,
3. **wagi domyślne** — nawigacja jedzie dalej, tylko bez adaptacji do pogody.

Realizuje to `OfflineWeatherPolicy` w module `shared`.

Przy okazji doprecyzowano **okno obserwacji spadku ciśnienia**. Specyfikacja podaje próg
„> 2 hPa", ale nie mówi, w jakim czasie — a bez okna próg nie znaczy nic, bo 2 hPa na dobę
to zwykła zmiana pogody, a 2 hPa na godzinę to front. Przyjęto **3 godziny**, zgodnie
z konwencją meteorologiczną definiującą szybki spadek ciśnienia właśnie jako 2 hPa / 3 h.
Próg i okno są konfigurowalne w `StormDetector`.

Spadek liczymy od **najwyższego** odczytu w oknie, nie od najstarszego — dzięki temu
wykrywamy również sytuację, w której ciśnienie najpierw jeszcze rosło, a dopiero potem
zaczęło gwałtownie spadać.

## Konsekwencje

**Pozytywne**

- Znika okno ślepoty na początku trybu offline — czyli w praktyce na początku każdego
  wjazdu w las czy w góry.
- Storm Mode reaguje na pomiar bieżący, a nie na przeterminowaną prognozę.
- Reguła pierwszeństwa jest zapisana i przetestowana, więc nie zostanie odtworzona
  różnie na każdej platformie.

**Negatywne**

- Barometr jest odpytywany dłużej, co ma niezerowy koszt energetyczny. Jest on jednak
  o rzędy wielkości niższy niż koszt GPS, którym steruje maszyna stanów z sekcji 7.
- Storm Mode może przesłonić świeżą prognozę, jeśli barometr zgłosi spadek, którego
  prognoza nie przewidywała. Uznajemy to za zachowanie pożądane: pomiar lokalny wie
  o mikroskali więcej niż prognoza obszarowa.
- Próg 3 godzin jest założeniem przyjętym z konwencji meteorologicznej, nie wynikiem
  pomiarów na realnych przejazdach. Wymaga weryfikacji przed wydaniem produkcyjnym.

## Rozważane alternatywy

### Pozostawienie sekwencji z pierwszej wersji

Prostsze w opisie i wierne pierwotnemu diagramowi. Odrzucone, bo zostawia znaną lukę
w funkcji, której jedynym zadaniem jest ostrzeganie przed niebezpieczną pogodą.

### Nasłuch barometru zawsze, także w trybie online

Kuszące dla spójności, ale wtedy Storm Mode konkurowałby z modułem AI, który ma dostęp do
pełnej prognozy z Open-Meteo i widzi więcej niż jeden czujnik ciśnienia. Barometr pozostaje
mechanizmem awaryjnym na czas braku łączności. Warto do tego wrócić, jeśli okaże się, że
lokalny pomiar wyprzedza prognozę na tyle, by miało to wartość również online.

### Wyzwalanie Storm Mode dopiero przy potwierdzeniu z dwóch źródeł

Ograniczyłoby fałszywe alarmy, ale offline drugie źródło z definicji nie istnieje.
Nie do zastosowania w scenariuszu, którego dotyczy sekcja 8.
