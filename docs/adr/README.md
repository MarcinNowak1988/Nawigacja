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
| [0001](0001-silnik-trasowania-per-platforma.md) | Silnik trasowania jest kodem per-platforma, nie wspólnym | Zaakceptowany |
| [0002](0002-model-wag-tylko-podwyzszajacy.md) | Model wag wyłącznie podwyższający | Zaakceptowany |
| [0003](0003-offline-mbtiles.md) | Mapy offline na plikach `.mbtiles` | Zaakceptowany |
| [0004](0004-warunek-wejscia-w-stan-stationary.md) | Wejście w stan STATIONARY wymaga potwierdzenia z akcelerometru | Zaakceptowany |
| [0005](0005-barometr-nasluchiwany-rownolegle.md) | Barometr nasłuchiwany równolegle z buforem pogodowym | Zaakceptowany |
