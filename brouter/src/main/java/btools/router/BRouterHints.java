package btools.router;

import java.util.ArrayList;
import java.util.List;

/**
 * Dostęp do podpowiedzi nawigacyjnych BRoutera.
 *
 * Pola klasy {@link VoiceHint} — {@code cmd}, {@code indexInTrack}, {@code ilon}, {@code ilat}
 * — są pakietowo-prywatne, podobnie jak lista w {@link VoiceHintList} i stałe komend. Z kodu
 * aplikacji nie da się ich odczytać.
 *
 * Ta klasa leży więc **celowo w pakiecie {@code btools.router}**, choć jest nasza: to jedyny
 * sposób, żeby dostać się do manewrów bez przepuszczania trasy przez format tekstowy (GPX
 * czy JSON) i parsowania go z powrotem. Zamiana danych na tekst i z powrotem byłaby wolniejsza
 * i wprowadzała własną klasę błędów tam, gdzie wystarczy odczyt pola.
 *
 * Plik należy do naszego modułu, nie do podmodułu z BRouterem — aktualizacja BRoutera go nie
 * nadpisze. Jeśli kiedyś zmienią nazwy tych pól, kompilacja tutaj powie o tym wprost, zamiast
 * dawać ciche zero manewrów.
 */
public final class BRouterHints {

    private BRouterHints() {
    }

    /** Jeden manewr w postaci nadającej się do przeniesienia do warstwy wspólnej. */
    public static final class Hint {
        public final int commandCode;
        public final int indexInTrack;
        public final double latitude;
        public final double longitude;

        Hint(int commandCode, int indexInTrack, double latitude, double longitude) {
            this.commandCode = commandCode;
            this.indexInTrack = indexInTrack;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    /** Podpowiedzi wyznaczonej trasy; pusta lista, gdy silnik ich nie wygenerował. */
    public static List<Hint> of(OsmTrack track) {
        List<Hint> hints = new ArrayList<>();
        if (track == null || track.voiceHints == null) {
            return hints;
        }
        for (VoiceHint hint : track.voiceHints.list) {
            hints.add(
                new Hint(
                    hint.cmd,
                    hint.indexInTrack,
                    // BRouter trzyma współrzędne jako mikrostopnie przesunięte o +90/+180.
                    hint.ilat / 1000000.0 - 90.0,
                    hint.ilon / 1000000.0 - 180.0
                )
            );
        }
        return hints;
    }

    // Kody komend BRoutera. Powtórzone tutaj jako publiczne, bo oryginały są pakietowo-prywatne
    // i nie da się ich zaimportować w Kotlinie.
    public static final int CONTINUE = VoiceHint.C;
    public static final int TURN_LEFT = VoiceHint.TL;
    public static final int TURN_SLIGHTLY_LEFT = VoiceHint.TSLL;
    public static final int TURN_SHARPLY_LEFT = VoiceHint.TSHL;
    public static final int TURN_RIGHT = VoiceHint.TR;
    public static final int TURN_SLIGHTLY_RIGHT = VoiceHint.TSLR;
    public static final int TURN_SHARPLY_RIGHT = VoiceHint.TSHR;
    public static final int KEEP_LEFT = VoiceHint.KL;
    public static final int KEEP_RIGHT = VoiceHint.KR;
    public static final int U_TURN_LEFT = VoiceHint.TLU;
    public static final int U_TURN_RIGHT = VoiceHint.TRU;
    public static final int OFF_ROUTE = VoiceHint.OFFR;
    public static final int ROUNDABOUT = VoiceHint.RNDB;
    public static final int ROUNDABOUT_LEFT = VoiceHint.RNLB;
    public static final int U_TURN = VoiceHint.TU;
    public static final int BEELINE = VoiceHint.BL;
    public static final int EXIT_LEFT = VoiceHint.EL;
    public static final int EXIT_RIGHT = VoiceHint.ER;
    public static final int END = VoiceHint.END;
}
