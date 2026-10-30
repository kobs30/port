package ai.portscan.core;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class ContainerAssemblerTest {
    @Test public void knownChecksumAndRemainderTen() {
        assertEquals(3, ContainerCode.checkDigit("CSQU305438"));
        assertTrue(ContainerCode.valid("CSQU3054383"));
        assertFalse(ContainerCode.valid("CSQU3054384"));
        assertFalse(ContainerCode.valid("CSQA3054383"));
        // Independent arithmetic example: sum = 5528, remainder = 6.
        assertEquals(6, ContainerCode.checkDigit("MSCU123456"));
        boolean foundRemainderTen = false;
        for (int n = 0; n < 100; n++) {
            String body = String.format(java.util.Locale.ROOT, "AAAU%06d", n);
            int total = 10 + 20 + 40 + 32 * 8;
            for (int i = 4; i < 10; i++) total += (body.charAt(i) - '0') * (1 << i);
            if (total % 11 == 10) { assertEquals(0, ContainerCode.checkDigit(body)); foundRemainderTen = true; }
        }
        assertTrue(foundRemainderTen);
    }
    @Test public void assemblesCurvedSurfaceWithoutAnyCompleteFrame() {
        var a = new ContainerAssembler();
        a.accept(List.of("CSQU 3054"));
        a.accept(List.of("3054383"));
        var s = a.snapshot();
        assertEquals("CSQU3054383", s.candidates.get(0).number);
        assertTrue(s.candidates.get(0).assembled);
        assertFalse(s.candidates.get(0).repeated);
        a.accept(List.of("CSQU3054"));
        s = a.accept(List.of("3054383"));
        assertTrue(s.candidates.get(0).repeated);
    }
    @Test public void reverseOrderAndThreeStagesWork() {
        var a = new ContainerAssembler();
        a.accept(List.of("54383"));
        a.accept(List.of("305438"));
        var s = a.accept(List.of("CSQU305"));
        assertEquals("CSQU3054383", s.candidates.get(0).number);
    }
    @Test public void doesNotInventMissingDigitOrJoinDisconnectedParts() {
        var a = new ContainerAssembler();
        a.accept(List.of("CSQU"));
        assertTrue(a.accept(List.of("3054383")).candidates.isEmpty());
        a.clear();
        var s = a.accept(List.of("CSQU305438"));
        assertTrue(s.candidates.isEmpty());
        assertEquals("CSQU305438", s.partial);
    }
    @Test public void keepsConflictsAndFailedChecksumVisible() {
        var a = new ContainerAssembler();
        a.accept(List.of("CSQU3054"));
        var s = a.accept(List.of("3054383", "3054384"));
        assertEquals(2, s.candidates.size());
        assertTrue(s.candidates.get(0).checksumValid);
        assertFalse(s.candidates.get(1).checksumValid);
    }
    @Test public void duplicateOcrRegionsAreNotIndependentFrames() {
        var a = new ContainerAssembler();
        var s = a.accept(List.of("CSQU3054383", "CSQU 305438 3"));
        assertFalse(s.candidates.get(0).repeated);
        assertTrue(a.accept(List.of("CSQU3054383")).candidates.get(0).repeated);
    }
    @Test public void newSweepCannotReuseOldFragments() {
        var a = new ContainerAssembler();
        a.accept(List.of("CSQU3054"));
        a.clear();
        assertTrue(a.accept(List.of("3054383")).candidates.isEmpty());
    }
    @Test public void handlesSplitLinesButDoesNotCorrectLookalikeLetters() {
        var a = new ContainerAssembler();
        assertTrue(a.accept(List.of("CSQU\n305438\n[3]")).candidates.get(0).checksumValid);
        a.clear();
        assertTrue(a.accept(List.of("CSQU3O54383")).candidates.isEmpty());
        a.clear();
        assertTrue(a.accept(List.of("20261234567890 MAXGROSS 30480")).candidates.isEmpty());
    }
    @Test public void buffersAreBounded() {
        var a = new ContainerAssembler();
        for (int i = 100; i < 400; i++) a.accept(List.of(Integer.toString(i)));
        assertTrue(a.snapshot().limited);
        assertTrue(a.snapshot().fragments.size() <= 10);
    }
}
