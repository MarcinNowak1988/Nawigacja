# Pająk i Muchy

Gra na boku, poza aplikacją nawigacyjną: pac-manowa mechanika przeniesiona
na **pajęczynę biegunową** zamiast prostokątnego labiryntu.

Otwórz [`pajak-i-muchy.html`](pajak-i-muchy.html) w przeglądarce — to jeden
samodzielny plik, bez zależności, bez sieci, bez budowania.

## Zasady

- Chodzisz pająkiem po nitkach i zjadasz **muchy** (10 pkt) — w węzłach i w połowie każdej nici.
- Cztery **osy** chodzą po tej samej sieci. Dotknięcie kosztuje życie; masz trzy.
- Cztery **krople rosy** (50 pkt) odwracają role na kilka sekund: osy uciekają,
  a zjedzenie ich daje 200, 400, 800, 1600 pkt.
- Wyczyszczenie sieci daje premię i **nową, wygenerowaną sieć** — szybszą.

Strzałki albo `WASD` wskazują kierunek na ekranie; `Spacja` pauzuje.
Na telefonie: przesunięcie palcem po sieci albo krzyżak pod planszą.

## Jak to działa

- **Sieć** to graf biegunowy: 7 pierścieni po 16 szprych plus gniazdo w środku
  (113 węzłów). Powstaje z ziarna zależnego od poziomu — drzewo rozpinające
  gwarantuje spójność, plecionka dorzuca pętle, a osobny przebieg usuwa ślepe
  zaułki. Nici dobierane są parami z odbiciem lustrzanym, więc sieć jest symetryczna.
  Promienie z gniazda i zewnętrzna obręcz są stałe — obręcz to korytarz ucieczki.
- **Sterowanie** działa w przestrzeni ekranu, nie w układzie sieci: strzałka w górę
  wybiera tę nić, która faktycznie prowadzi w górę ekranu. Bez tego sterowanie
  odwracałoby się na dolnej połowie pajęczyny.
- **Osy** liczą najkrótszą drogę (BFS) do własnego celu: Cień goni wprost,
  Zasadzka celuje cztery węzły przed pająka, Lustro zachodzi z odbitej strony sieci,
  Włóczęga dołącza dopiero z bliska. Fazy pościgu i rozejścia się po rogach
  zmieniają się jak w pierwowzorze.

Poprawność generatora sieci (spójność, symetria, brak ślepych zaułków) i przebieg
rozgrywki sprawdzono w przeglądarce przed wypchnięciem.
