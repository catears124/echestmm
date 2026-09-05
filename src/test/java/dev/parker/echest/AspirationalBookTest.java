package dev.parker.echest;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pricing into a book whose upper half is aspirational.
 *
 * <p>The live obsidian book had 45 listings between 1,000/item and well past 7,400/item - 23 of
 * them above the price the desk chose. Those upper listings are not competition, they are wishes,
 * and they never trade. The desk listed a stack at $473,600 because it evaluated that queue with a
 * clearing rate of 5.192 units/s borrowed from the ender chest desk, which made a 22-deep queue
 * look four seconds long.
 *
 * <p>With the desk's own measured rate the arithmetic goes the other way, and that is what these
 * pin: revenue rate must prefer the front of a cheap queue over the back of an expensive one when
 * the queue really moves at the speed we measured.
 */
final class AspirationalBookTest {
    private static final Liquidity.Config CFG = Liquidity.Config.defaults();

    /** The shape of the real obsidian book: cheap real supply, then a long aspirational tail. */
    private static List<Liquidity.Level> obsidianBook() {
        List<Liquidity.Level> book = new ArrayList<>();
        book.add(new Liquidity.Level(1_000, 22, 1_408));
        book.add(new Liquidity.Level(7_400, 1, 64));
        book.add(new Liquidity.Level(20_000, 22, 1_408));
        return book;
    }

    @Test
    void evenABorrowedFastRateNoLongerJustifiesTheAspirationalTail() {
        // The live failure listed a stack at $473,600 with a borrowed 5.192 units/s. Measuring
        // depth in units rather than listings is enough on its own to prevent it: 23 listings of
        // 64 is 1,472 units, and even at that optimistic rate the queue is not worth joining.
        Liquidity.Quote borrowed = Liquidity.quote(obsidianBook(), List.of(), 5.192, 64, CFG);
        assertTrue(borrowed.unitPrice() < 7_400,
                "a borrowed rate must no longer reach the aspirational tail, got "
                        + borrowed.unitPrice() + " (" + borrowed.reason() + ")");
    }

    @Test
    void countingListingsInsteadOfUnitsIsWhatMisledIt() {
        // The arithmetic behind the bug, pinned directly: on a 64-stack desk the two depth
        // measures differ by the stack size, and the wait is depth over a units-per-second rate.
        long units = Liquidity.unitsAhead(obsidianBook(), List.of(), 19_900, 64);
        int listings = Liquidity.queueAhead(obsidianBook(), List.of(), 19_900);
        assertTrue(units == 1_472, "expected 1,472 units of real depth, got " + units);
        assertTrue(listings == 23, "expected 23 listings, got " + listings);
        assertTrue(units > listings * 60L, "the stack size is the whole error");
    }

    @Test
    void theDesksOwnSlowRatePricesAtTheFrontOfTheCheapQueue() {
        // Obsidian's own clearing rate is nothing like an ender chest's. At a realistic rate the
        // wait behind 22 listings dominates, and the front of the book wins on revenue per second.
        Liquidity.Quote own = Liquidity.quote(obsidianBook(), List.of(), 0.10, 64, CFG);
        assertTrue(own.unitPrice() < 7_400,
                "expected a price at the cheap end, got " + own.unitPrice() + " (" + own.reason() + ")");
    }

    @Test
    void anUnmeasuredRateUndercutsTheFloorToLearnOne() {
        // A freshly switched desk has measured nothing, which is the state obsidian should have
        // been in. Undercutting the real floor is how it buys its first data point.
        Liquidity.Quote fresh = Liquidity.quote(obsidianBook(), List.of(), Double.NaN, 64, CFG);
        assertTrue(fresh.unitPrice() <= 1_000,
                "expected an undercut of the 1,000 floor, got " + fresh.unitPrice());
        assertTrue(fresh.reason().contains("unmeasured"), fresh.reason());
    }
}
