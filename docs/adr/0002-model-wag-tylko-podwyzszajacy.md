# ADR-0002: Model wag wyłącznie podwyższający

- **Status:** Zaakceptowany
- **Data:** 26 lipca 2026
- **Dotyczy:** sekcji 5.2, 5.3 i 6.2 [dokumentacji technicznej](../DOKUMENTACJA_TECHNICZNA.md)

## Kontekst

Sekcja 5.2 specyfikacji definiuje trzy zakresy wag: `< 1.0` przyciąga segment, `> 1.0`
odstrasza, `999.0` całkowicie zakazuje. Sekcja 5.3 pokazuje to na przykładzie ulewy,
w której moduł AI zwraca `asphalt = 0.5` i `cycleway = 0.5` — czyli **obniża** wagi,
żeby premiować asfaltową ścieżkę rowerową.

To nie zadziała z GraphHopperem w konfiguracji, jakiej wymaga nawigacja mobilna.
[Dokumentacja custom models](https://github.com/graphhopper/graphhopper/blob/master/docs/core/custom-models.md)
stawia warunek wprost: przy zmianie custom modelu **w czasie działania** z włączonym
speedupem Landmarks (LM) wagi krawędzi mogą być wyłącznie **podwyższane, nigdy obniżane**.

Powód jest algorytmiczny. Landmarks to heurystyka A\* wyliczana wstępnie na etapie
przygotowania grafu. Heurystyka pozostaje dopuszczalna (nie przeszacowuje kosztu) tylko
wtedy, gdy koszty rzeczywiste nie spadają poniżej tych, dla których ją policzono.
Obniżenie wagi w runtime łamie to założenie, a wraz z nim gwarancję optymalności trasy.

Rezygnacja z LM usunęłaby ograniczenie, ale kosztem czasu wyznaczania trasy — na
urządzeniu mobilnym, offline, przy trasach rowerowych liczonych na dziesiątki kilometrów,
to zła zamiana.

## Decyzja

**Model wag jest wyłącznie podwyższający.** Waga neutralna wynosi `1.0`, każdy mnożnik
jest `>= 1.0`, a `999.0` pozostaje sentinelem zakazu wjazdu.

Preferencje wyrażamy przez **karanie gorszych opcji**, nie nagradzanie lepszych: zamiast
obniżać wagę asfaltu, podnosimy wagę błota i szutru.

Odpowiedzi modelu AI zawierające wagi `< 1.0` **nie są odrzucane** — są normalizowane.
Robi to `WeightTable.normalizedToIncreaseOnly()` w module `shared`.

### Dlaczego normalizacja nie zmienia trasy

Mnożnik z danego wymiaru (nawierzchnia, infrastruktura) wchodzi do kosztu **każdej**
krawędzi — dla krawędzi nieotagowanych jest to waga domyślna. Przeskalowanie wszystkich
wag jednego wymiaru przez tę samą dodatnią stałą podnosi więc koszty wszystkich tras
proporcjonalnie, nie zmieniając ich uporządkowania. Trasa optymalna zostaje ta sama.

Wystarczy dobrać skalę tak, by minimum tablicy wyniosło `1.0`. Wagi równe `999.0` są
sentinelem, nie liczbą — nie biorą udziału w wyznaczaniu minimum i nie są skalowane.

Na przykładzie z sekcji 5.3 (`mud = 999.0`, `asphalt = 0.5`, `cycleway = 0.5`):

| Wymiar | Przed | Po normalizacji |
|---|---|---|
| nawierzchnia: `asphalt` | `0.5` | `1.0` |
| nawierzchnia: domyślna | `1.0` | `2.0` |
| nawierzchnia: `mud` | `999.0` | `999.0` (bez zmian) |
| infrastruktura: `cycleway` | `0.5` | `1.0` |
| infrastruktura: domyślna | `1.0` | `2.0` |

Stosunek kosztu asfaltowej ścieżki rowerowej do drogi domyślnej: przed normalizacją
`(0.5 × 0.5) / (1.0 × 1.0) = 0.25`, po normalizacji `(1.0 × 1.0) / (2.0 × 2.0) = 0.25`.
Zapowiadana w sekcji 5.3 czterokrotna przewaga zostaje zachowana co do joty — pilnuje
tego test `po normalizacji asfaltowa sciezka jest czterokrotnie tansza od domyslnej`.

## Konsekwencje

**Pozytywne**

- Wagi z modułu AI można podawać GraphHopperowi w runtime bez wyłączania speedupu LM.
- Kontrakt JSON z sekcji 6.2 zostaje nietknięty — model AI może dalej zwracać `0.5`,
  normalizacja dzieje się po stronie aplikacji. Nie trzeba przestrajać promptu.
- Semantyka „wagi rosną" jest prostsza do testowania: dowolna waga poniżej `1.0`
  w wyniku normalizacji to błąd, wychwytywany jednym asercją.

**Negatywne**

- Tabela w sekcji 5.2 traci wiersz `< 1.0` jako stan docelowy — pozostaje wyłącznie
  jako dopuszczalne wejście od modelu AI, normalizowane przed użyciem.
- Wartości bezwzględne kosztów po normalizacji nie są porównywalne między kolejnymi
  odpowiedziami modelu AI (skala bywa różna). Porównywać wolno tylko w obrębie
  jednego zestawu wag.

## Rozważane alternatywy

### Odrzucanie wag poniżej 1.0

Prostsze w implementacji, ale zrzuca odpowiedzialność na prompt modelu AI, czyli na
najmniej niezawodny element układu. Pierwsza odpowiedź z `0.5` kończyłaby się fallbackiem
na wagi domyślne i cichą utratą adaptacji do pogody — dokładnie w ulewie, czyli wtedy,
gdy funkcja jest najbardziej potrzebna.

### Obcinanie wag do 1.0

Odrzucona, bo zmienia zachowanie trasowania. Obcięcie `asphalt = 0.5` do `1.0` kasuje
preferencję asfaltu zamiast ją zachować. Normalizacja przez skalowanie jest równie prosta,
a nie gubi intencji modelu.

### Wyłączenie speedupu Landmarks

Usuwa ograniczenie u źródła i pozwala zostawić model wag bez zmian. Odrzucona ze względu
na czas wyznaczania trasy na urządzeniu mobilnym. Warto wrócić do pomiaru, gdy powstanie
warstwa androidowa — jeśli różnica okaże się nieistotna dla realnych dystansów rowerowych,
ten ADR można zastąpić nowszym.
