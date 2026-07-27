# ADR-0008: Wyszukiwanie miejsc w Mapy.com, mapa zostaje na OpenStreetMap

- **Status:** **Wycofany 27 lipca 2026, przed wejściem w życie**
- **Data:** 27 lipca 2026
- **Dotyczy:** sekcji 3, 5.0 i 9 [dokumentacji technicznej](../DOKUMENTACJA_TECHNICZNA.md)
- **Wzmacnia:** [ADR-0006](0006-mapy-offline-przez-offlinemanager.md), [ADR-0007](0007-aplikacja-online-z-zapisanymi-regionami.md)

> **Dlaczego wycofany.** Właściciel produktu zdecydował o rezygnacji z Mapy.com, zanim
> decyzja trafiła do jakiegokolwiek wydania — klucz API nigdy nie został skonfigurowany,
> więc aplikacja przez cały czas korzystała z Nominatima. Treść ADR zostaje nietknięta,
> bo najważniejsze ustalenie zachowuje ważność niezależnie od dostawcy: **regulamin
> Mapy.com zabrania buforowania kafelków, co wyklucza je jako źródło mapy podkładowej
> dopóki istnieją zapisane regiony offline.** Port `Geocoder`, który przy okazji powstał,
> pozostaje w kodzie.

## Kontekst

Wyszukiwanie miejsc opiera się na publicznej instancji Nominatim. Dla polskich adresów jej
trafność jest przeciętna, a to pierwszy krok każdego przejazdu — jeśli użytkownik nie znajdzie
celu, reszta aplikacji nie ma znaczenia.

Rozważaliśmy przejście na [Mapy.com](https://developer.mapy.com/) w całości: kafelki mapy,
trasowanie i wyszukiwanie. Ich mapy turystyczne i rowerowe dla Europy Środkowej są wyraźnie
lepsze od tego, co renderujemy dziś, a darmowy próg (250 000 kredytów miesięcznie w taryfie
Basic) z zapasem pokrywa użytek osobisty.

## Decyzja

**Na Mapy.com przechodzi wyłącznie wyszukiwanie miejsc. Kafelki mapy i trasowanie zostają
na OpenStreetMap.**

Rozstrzygnął o tym regulamin, nie preferencje. Warunki korzystania z API Mapy.com zabraniają
„przechowywania lub buforowania pojedynczych kafelków ani wyników funkcji API" oraz
„wstępnego buforowania, przechowywania lub eksportowania" danych z serwisu.

To jest zakaz dokładnie tej funkcji, którą aplikacja ma: `OfflineManager` MapLibre pobiera
kafelki i trzyma je na urządzeniu ([ADR-0006](0006-mapy-offline-przez-offlinemanager.md)).
Po [ADR-0007](0007-aplikacja-online-z-zapisanymi-regionami.md) zapisane regiony są **jedynym**
mechanizmem offline, jaki produktowi został — trasowanie już wcześniej stało się sieciowe.
Zamiana kafelków oznaczałaby więc, że bez zasięgu aplikacja nie pokazuje **niczego**.

Wyszukiwania ten zakaz nie dotyka w praktyce: wyników i tak nie przechowujemy, bo służą
jednorazowo do wskazania punktu trasy.

Wynikające z tego ustalenia:

- Powstaje port `Geocoder` w warstwie wspólnej — ten sam zabieg co `RouteEngine`
  z [ADR-0001](0001-silnik-trasowania-per-platforma.md). Zmiana dostawcy wyszukiwania jest
  odtąd podmianą jednej klasy.
- Bez klucza API aplikacja **wraca do Nominatima**. Inaczej każdy build bez sekretu dawałby
  aplikację z martwym wyszukiwaniem — nie do przetestowania i nie do wydania.
- Klucz wstrzykiwany jest przy budowaniu z sekretu `MAPY_API_KEY`, nie leży w repozytorium.

## Konsekwencje

**Pozytywne**

- Trafność wyszukiwania polskich adresów rośnie tam, gdzie ma to największe znaczenie.
- Zapisane regiony offline zostają nietknięte, więc mapa w lesie dalej działa.
- Port `Geocoder` domyka lukę w architekturze: dotąd tylko trasowanie miało jawną granicę
  dostawcy, mimo że wyszukiwanie jest tak samo wymienialne.

**Negatywne**

- **Klucz API trafia do APK i da się go stamtąd wyjąć.** To nieusuwalna właściwość kluczy
  w aplikacjach mobilnych, nie przeoczenie w konfiguracji. Przy repozytorium publicznym
  i APK rozdawanym w wydaniach oznacza to, że cudzy ruch może obciążyć konto właściciela
  klucza. Limit miesięczny w panelu Mapy.com jest jedyną realną ochroną.
- **Aplikacja ma teraz dwie ścieżki wyszukiwania** — z kluczem i bez. Każda usterka wymaga
  ustalenia, która była w użyciu; dlatego nazwa dostawcy jest pokazywana w oknie wyników.
- Do usług zewnętrznych dochodzi kolejna, a wraz z nią kolejny zakres danych opuszczających
  urządzenie (sekcja 9).
- **Kształt odpowiedzi przyjęto bez dostępu do dokumentacji.** Środowisko, w którym powstał
  klient, nie ma dostępu do `api.mapy.com`, więc nazw pól nie dało się potwierdzić u źródła.
  Parsowanie jest z tego powodu tolerancyjne — nieznana struktura daje „nic nie znalazłem",
  a nie awarię — ale **wymaga sprawdzenia na pierwszym prawdziwym zapytaniu**.

## Rozważane alternatywy

### Pełne przejście na Mapy.com (mapa, trasowanie, wyszukiwanie)

Rozważane i **odrzucone ze względu na regulamin**, nie na jakość — mapy Mapy.com są dla
rowerzysty w Polsce lepsze. Koszt byłby jednak konkretny: utrata zapisanych regionów, czyli
ostatniej rzeczy działającej bez zasięgu. Gdyby priorytety się odwróciły i tryb offline
przestał mieć znaczenie, decyzja jest odwracalna: `OfflineManager` znika, a ADR-0006 zostaje
zastąpiony nowszym.

### Mapy.com jako drugi, przełączalny styl mapy

Mapa Mapy.com w zasięgu, styl OpenStreetMap pod zapisane regiony. Nie łamie regulaminu
i daje lepszą mapę, ale podwaja obsługę źródeł map i mnoży przypadki brzegowe przy pobieraniu
regionów. Warte rozważenia, gdy wyszukiwanie się sprawdzi.

### Zostawienie Nominatima

Bez kosztu i bez klucza. Odrzucone, bo trafność wyszukiwania jest pierwszym krokiem każdego
przejazdu i najbardziej odczuwalną słabością aplikacji w codziennym użyciu.
