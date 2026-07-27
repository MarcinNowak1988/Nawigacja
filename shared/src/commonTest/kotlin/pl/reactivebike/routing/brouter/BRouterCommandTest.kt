package pl.reactivebike.routing.brouter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BRouterCommandTest {

    @Test
    fun known_codes_map_to_their_command() {
        assertEquals(BRouterCommand.TURN_LEFT, BRouterCommand.fromCode(2))
        assertEquals(BRouterCommand.TURN_RIGHT, BRouterCommand.fromCode(5))
        assertEquals(BRouterCommand.ROUNDABOUT, BRouterCommand.fromCode(13))
        assertEquals(BRouterCommand.END, BRouterCommand.fromCode(100))
    }

    /** Nieznany kod ma zwrócić `null`, a nie najbliższy pasujący manewr. */
    @Test
    fun an_unknown_code_maps_to_nothing() {
        assertNull(BRouterCommand.fromCode(0))
        assertNull(BRouterCommand.fromCode(42))
        assertNull(BRouterCommand.fromCode(-1))
    }

    /** Cisza w nawigacji jest gorsza niż ogólnik — nieznany kod dostaje zastępnik. */
    @Test
    fun an_unknown_code_still_produces_something_to_say() {
        assertEquals("Jedź dalej", BRouterCommand.instructionFor(999))
    }

    @Test
    fun known_codes_produce_their_own_instruction() {
        assertEquals("Skręć w lewo", BRouterCommand.instructionFor(2))
        assertEquals("Wjedź na rondo", BRouterCommand.instructionFor(13))
    }

    @Test
    fun every_command_has_a_non_blank_instruction() {
        BRouterCommand.entries.forEach { command ->
            assertTrue(command.instruction.isNotBlank(), "$command bez instrukcji")
        }
    }

    /** Kody muszą być unikalne, inaczej `fromCode` po cichu zgubiłby jeden z manewrów. */
    @Test
    fun codes_are_unique() {
        val codes = BRouterCommand.entries.map { it.code }

        assertEquals(codes.size, codes.toSet().size, "powtorzone kody: $codes")
    }

    /**
     * „Jedź prosto" nie jest manewrem do zapowiadania — zapowiadanie go co skrzyżowanie
     * zamieniłoby prowadzenie w szum.
     */
    @Test
    fun going_straight_is_not_an_actionable_manoeuvre() {
        assertFalse(BRouterCommand.CONTINUE.isActionable)
        assertFalse(BRouterCommand.BEELINE.isActionable)
        assertFalse(BRouterCommand.OFF_ROUTE.isActionable)
    }

    @Test
    fun turns_and_roundabouts_are_actionable() {
        assertTrue(BRouterCommand.TURN_LEFT.isActionable)
        assertTrue(BRouterCommand.ROUNDABOUT.isActionable)
        assertTrue(BRouterCommand.U_TURN.isActionable)
        assertTrue(BRouterCommand.END.isActionable)
    }
}
