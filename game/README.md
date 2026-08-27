# Pająk i Muchy

Gra na boku, poza aplikacją nawigacyjną: pac-manowa mechanika na planszy,
która naprawdę jest zbudowana jak w pierwowzorze — prostokątna siatka
korytarzy, nie okrąg.

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
- **Osy** chodzą po tej samej planszy, coraz liczniejsze z każdą kolejną planszą.
  Dotknięcie kosztuje życie.
- Cztery **krople rosy** przy rogach (50 pkt) odwracają role na kilka sekund: osy uciekają,
  a zjedzenie ich daje 200, 400, 800, 1600 pkt.
- Wyczyszczenie planszy daje premię i **nową, wygenerowaną planszę** — szybszą.

Strzałki albo `WASD` wskazują kierunek; `Spacja` pauzuje.
Na telefonie: przesunięcie palcem po planszy albo krzyżak pod nią.

## Jak to działa

- **Plansza** to prostokątna siatka 9×9 — jak w pierwowzorze, nie okrąg. Węzeł
  `(wiersz,kolumna)` ma numer `wiersz*9+kolumna`; gniazdo os stoi w środku.
  Powstaje z ziarna zależnego od poziomu — drzewo rozpinające gwarantuje
  spójność, plecionka dorzuca pętle, a osobny przebieg usuwa ślepe zaułki.
  Nici dobierane są parami z odbiciem względem pionowej osi środkowej, więc
  plansza jest symetryczna jak klasyczne plansze Pac-Mana. Promienie z gniazda
  i cała obwódka planszy są stałe — obwódka to korytarz ucieczki dookoła.
- **Sterowanie jest teraz dokładne, nie przybliżone.** Na dawnej sieci kołowej
  większość nici biegła pod kątem (szprychy co 30°), więc strzałka „w górę"
  musiała zgadywać najbliższy kierunek progiem podobieństwa — i czasem nie
  trafiała w żadną nić. Na siatce osiowej każdy kierunek jest zawsze dokładnie
  jednym z czterech sąsiadów węzła: sprawdzenie „czy tam prowadzi nić" jest
  proste i jednoznaczne, bez przybliżeń. Wskazany kierunek jest buforowany
  jak w oryginalnym Pac-Manie — jeśli w danym węźle nić w tę stronę jeszcze
  nie istnieje, gra próbuje ponownie w każdym kolejnym węźle, aż się uda.
  Pająk zatrzymuje się tylko tam, gdzie naprawdę nie ma dokąd iść bez
  wskazania — dokładnie jak Pac-Man przy ścianie — i rusza natychmiast,
  bez czekania na klatkę, gdy tylko wskazany kierunek stanie się możliwy.
- **Osy** liczą najkrótszą drogę (BFS) do własnego celu: Cień goni wprost,
  Zasadzka celuje cztery węzły przed pająka, Lustro zachodzi z odbitej strony
  planszy, Włóczęga dołącza dopiero z bliska. Fazy pościgu i rozejścia się po
  rogach zmieniają się jak w pierwowzorze.
- Prędkości liczone są w komórkach na sekundę, nie w pikselach — tempo gry
  nie zależy od wielkości płótna ani gęstości siatki.

Poprawność generatora planszy (spójność, symetria, brak ślepych zaułków,
wszystkie nici osiowe) i realną skuteczność sterowania zmierzono automatycznymi
testami w przeglądarce, nie samym wrażeniem: przypadki, w których naciśnięcie
klawisza nie skutkowało natychmiastowym skrętem, sprawdzono osobno — we
wszystkich pająk poprawnie zapamiętał kierunek i czekał na węzeł, z którego
faktycznie prowadzi tam nić, zamiast zawodzić.
