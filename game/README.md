# Pająk i Muchy

Gra na boku, poza aplikacją nawigacyjną: pac-manowa mechanika przeniesiona
na **pajęczynę biegunową** zamiast prostokątnego labiryntu.

Otwórz [`pajak-i-muchy.html`](pajak-i-muchy.html) w przeglądarce — to jeden
samodzielny plik, bez zależności, bez sieci, bez budowania.

## Przebieg gry

Zanim pająk wyruszy na łowy, czeka go **quiz matematyczny** — pięć zadań
(dodawanie i odejmowanie do 20, brakujący składnik, tabliczka mnożenia 2/3/5,
połowa z liczby), poziom trudności 7–8 lat. Zła odpowiedź nie kończy zadania —
dziecko próbuje dalej, traci tylko bonus za trafienie za pierwszym razem.
Komplet bez pomyłki daje dodatkowe, czwarte życie na start.

## Zasady

- Chodzisz pająkiem po nitkach i zjadasz **muchy** (10 pkt) — w węzłach i w połowie każdej nici.
- **Osy** chodzą po tej samej sieci, coraz liczniejsze z każdą kolejną siecią.
  Dotknięcie kosztuje życie.
- Cztery **krople rosy** (50 pkt) odwracają role na kilka sekund: osy uciekają,
  a zjedzenie ich daje 200, 400, 800, 1600 pkt.
- Wyczyszczenie sieci daje premię i **nową, wygenerowaną sieć** — szybszą.

Strzałki albo `WASD` wskazują kierunek na ekranie; `Spacja` pauzuje.
Na telefonie: przesunięcie palcem po sieci albo krzyżak pod planszą.

## Jak to działa

- **Sieć** to graf biegunowy: 5 pierścieni po 12 szprych plus gniazdo w środku
  (61 węzłów). Powstaje z ziarna zależnego od poziomu — drzewo rozpinające
  gwarantuje spójność, plecionka dorzuca pętle, a osobny przebieg usuwa ślepe
  zaułki. Nici dobierane są parami z odbiciem lustrzanym, więc sieć jest symetryczna.
  Promienie z gniazda i zewnętrzna obręcz są stałe — obręcz to korytarz ucieczki.
  Dodatkowy przebieg gwarantuje też, że z **każdego** węzła da się pójść wprost
  do środka albo na zewnątrz — bez tego w niektórych miejscach żadna nić nie
  szła „w górę ekranu" i strzałka trafiała w pustkę.
- **Sterowanie** działa w przestrzeni ekranu, nie w układzie sieci: strzałka w górę
  wybiera nić, która faktycznie prowadzi w górę ekranu, i to *najlepiej dopasowaną
  z dostępnych* — przy 12 szprychach co 30° tylko cztery są idealnie pionowe albo
  poziome, więc czekanie na idealne dopasowanie sprawiałoby wrażenie zawodzącego
  sterowania. Pająk nigdy nie staje w miejscu: bez wskazanego kierunku płynie dalej
  najbliższą dotychczasowemu kursowi nicią, a w ślepym zaułku zawraca sam.
- **Osy** liczą najkrótszą drogę (BFS) do własnego celu: Cień goni wprost,
  Zasadzka celuje cztery węzły przed pająka, Lustro zachodzi z odbitej strony sieci,
  Włóczęga dołącza dopiero z bliska. Fazy pościgu i rozejścia się po rogach
  zmieniają się jak w pierwowzorze.

Poprawność generatora sieci (spójność, symetria, brak ślepych zaułków, nić do
środka/na zewnątrz w każdym węźle) i realną skuteczność sterowania — nie tylko
wrażenie — zmierzono automatycznymi testami w przeglądarce: mediana czasu
reakcji na naciśnięcie klawisza spadła z 300 ms do 100 ms po poprawkach.
