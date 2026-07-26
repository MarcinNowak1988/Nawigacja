# ADR-0006: Mapy offline przez OfflineManager zamiast ręcznego zarządzania `.mbtiles`

- **Status:** Zaakceptowany
- **Data:** 26 lipca 2026
- **Dotyczy:** sekcji 3 i 8 [dokumentacji technicznej](../DOKUMENTACJA_TECHNICZNA.md)
- **Doprecyzowuje:** [ADR-0003](0003-offline-mbtiles.md)

## Kontekst

Sekcja 3 specyfikacji wskazuje pliki `.mbtiles` jako nośnik map offline, a [ADR-0003](0003-offline-mbtiles.md)
ustalił podział „jeden region = jeden plik `.mbtiles` = jedno źródło w stylu mapy".

Przy implementacji okazało się, że ten model rozwiązuje tylko połowę problemu. Plik
`.mbtiles` trzeba **skądś wziąć**. Nie istnieje powszechna usługa wydająca gotowe pliki
`.mbtiles` dla dowolnego regionu — trzeba je albo wygenerować samodzielnie z danych OSM
i hostować, albo zbudować własny mechanizm pobierania kafelek i składania ich w plik.
Jedno i drugie to osobna infrastruktura, której projekt nie ma i której nie potrzebuje,
żeby dowieźć mapę działającą bez zasięgu.

MapLibre Native ma na to gotowy mechanizm: `OfflineManager` pobiera dla zadanego obszaru
i zakresu powiększeń wszystkie zasoby stylu — kafelki, czcionki, sprite'y — i zapisuje je
w swojej bazie na urządzeniu. Renderer sięga po nie automatycznie, bez zmian w stylu.

Warto zauważyć, że oba rozwiązania trzymają dane w SQLite. Różnica nie leży w technologii,
tylko w tym, **kto zarządza plikiem**: przy `.mbtiles` my, przy `OfflineManager` biblioteka.

## Decyzja

Mapy offline pobieramy przez **`OfflineManager` MapLibre**. Użytkownik wskazuje obszar
widocznym fragmentem mapy i uruchamia pobieranie; zasoby lądują w bazie MapLibre.

Zakres powiększeń ograniczamy do **10–15**. Każdy kolejny poziom to czterokrotnie więcej
kafelków, a poziom 15 daje szczegółowość wystarczającą do jazdy rowerem.

Postęp odczytujemy **odpytywaniem** `getStatus`, a nie przez `OfflineRegionObserver`.
Efekt jest ten sam, a powierzchnia używanego API mniejsza — co przy kodzie pisanym bez
możliwości lokalnej kompilacji przekłada się wprost na mniejszą liczbę rund CI.

Ustalenia ADR-0003 dotyczące **samych plików `.mbtiles`** — jedno źródło na plik,
konieczność kopiowania z `assets/` na Androidzie — pozostają w mocy i będą potrzebne,
jeśli projekt kiedyś zacznie dystrybuować gotowe paczki map. Nie obowiązuje natomiast
model „jeden region = jeden plik `.mbtiles`", bo regionami zarządza teraz biblioteka.

## Konsekwencje

**Pozytywne**

- Mapa offline działa **bez własnej infrastruktury** — nie trzeba generować ani hostować
  paczek z kafelkami.
- Znika problem wielu źródeł w stylu, opisany w ADR-0003: `OfflineManager` obsługuje wiele
  regionów naraz, a renderer sam wybiera właściwe zasoby.
- Pobieranie obejmuje także czcionki i sprite'y stylu, o których łatwo zapomnieć przy
  ręcznym składaniu `.mbtiles`, a bez których mapa offline wyświetla się bez etykiet.

**Negatywne**

- Kafelki pobiera się z sieci **z góry**, więc pierwsze użycie regionu wymaga zasięgu.
  Nie da się wgrać mapy z zewnątrz, np. kablem przed wyjazdem w góry.
- Format bazy należy do MapLibre — dane nie są przenośne między aplikacjami ani łatwe
  do podejrzenia.
- Rozmiar pobrania zależy od zadanego obszaru i łatwo o nieuwagę: obszar wielkości
  województwa przy powiększeniu 15 to setki megabajtów. Interfejs pokazuje postęp
  i rozmiar, ale nie ostrzega z góry — do poprawy.

## Rozważane alternatywy

### Trzymanie się ADR-0003 i plików `.mbtiles`

Zgodne z literą sekcji 3 i pozwala wgrać mapę bez zasięgu. Odrzucone, bo wymaga
zbudowania i utrzymania procesu generowania oraz hostowania paczek — nieproporcjonalnie
dużo pracy jak na etap, na którym nie ma jeszcze trasowania.

Warto do tego wrócić, gdy pojawi się potrzeba dystrybucji przygotowanych regionów;
wtedy oba mechanizmy mogą współistnieć.

### Poleganie wyłącznie na pamięci podręcznej MapLibre

Renderer i tak buforuje obejrzane kafelki, więc trasa przejechana raz w zasięgu
wyświetli się offline. Odrzucone, bo to przypadek, nie funkcja: użytkownik nie ma nad
tym kontroli, a bufor jest czyszczony przy braku miejsca. Sekcja 8 obiecuje działanie
w lesie i w górach, gdzie nikt wcześniej nie jechał z zasięgiem.
