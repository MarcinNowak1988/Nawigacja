# Architecture Decision Records

Katalog zawiera zapisy decyzji architektonicznych (ADR) projektu ReactiveBike.

ADR powstaje wtedy, gdy decyzja jest kosztowna do odwrócenia albo gdy odbiega od tego,
co mówi [dokumentacja techniczna](../DOKUMENTACJA_TECHNICZNA.md). Celem jest zapisanie
**dlaczego** coś wygląda tak, a nie inaczej — kod pokazuje „jak", ADR tłumaczy „czemu".

## Format

Każdy ADR ma cztery sekcje: **Kontekst**, **Decyzja**, **Konsekwencje**, **Rozważane alternatywy**.

## Numeracja

Pliki nazywamy `NNNN-krotki-opis.md`, numer rośnie monotonicznie i nigdy nie jest ponownie
używany. ADR raz zaakceptowany nie jest edytowany merytorycznie — jeśli decyzja się zmienia,
powstaje nowy ADR, a stary dostaje status `Zastąpiony przez ADR-NNNN`.

## Statusy

| Status | Znaczenie |
|---|---|
| `Zaproponowany` | Do dyskusji, jeszcze nie obowiązuje |
| `Zaakceptowany` | Obowiązuje, kod ma być z nim zgodny |
| `Zastąpiony przez ADR-NNNN` | Nieaktualny, zastąpiony nowszą decyzją |

## Rejestr

| ADR | Tytuł | Status |
|---|---|---|
| [0001](0001-silnik-trasowania-per-platforma.md) | Silnik trasowania jest kodem per-platforma, nie wspólnym | Zaakceptowany, założenia zmienione przez ADR-0007 |
| [0002](0002-model-wag-tylko-podwyzszajacy.md) | Model wag wyłącznie podwyższający | Zaakceptowany |
| [0003](0003-offline-mbtiles.md) | Mapy offline na plikach `.mbtiles` | Bez zastosowania po ADR-0006 i ADR-0007 |
| [0004](0004-warunek-wejscia-w-stan-stationary.md) | Wejście w stan STATIONARY wymaga potwierdzenia z akcelerometru | Zaakceptowany |
| [0005](0005-barometr-nasluchiwany-rownolegle.md) | Barometr nasłuchiwany równolegle z buforem pogodowym | Zaakceptowany |
| [0006](0006-mapy-offline-przez-offlinemanager.md) | Mapy offline przez OfflineManager zamiast ręcznego `.mbtiles` | Zaakceptowany |
| [0007](0007-aplikacja-online-z-zapisanymi-regionami.md) | Aplikacja online z zapisanymi regionami mapy | Zaakceptowany |
| [0008](0008-wyszukiwanie-miejsc-w-mapy-com.md) | Wyszukiwanie miejsc w Mapy.com, mapa zostaje na OpenStreetMap | Wycofany przed wejściem w życie |
| [0009](0009-brouter-na-urzadzeniu.md) | Trasowanie rowerowe przez BRouter na urządzeniu | Zaakceptowany, wdrożenie w toku |
