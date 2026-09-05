package dev.parker.echest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a sweep is allowed to believe an item is worth.
 *
 * <p>The obsidian book was read at floor 1250/item on an evening when realised sales were nearer
 * 470. Pricing a sweep off that floor pays ~1060/item for stock worth half of it - the sweep would
 * be buying another seller's optimism with real money. So the resale figure a sweep uses is the
 * lower of what the book says and what we have actually been paid, and the sweep is refused
 * outright when neither exists.
 */
final class SweepAnchorTest {
    private static final Liquidity.Config CFG = Liquidity.Config.defaults();

    @Test
    void aThinExpensiveBookDoesNotJustifyOverpaying() {
        // Book: one seller asking 1,250/item. Our realised sales: 470/item.
        List<Liquidity.Level> book = List.of(new Liquidity.Level(1_000, 3, 192));
        // Priced off the book's own optimism the sweep goes ahead...
        assertTrue(Sweep.plan(book, 1_250, 5_000_000L, 1_000, 0, CFG).go());
        // ...but priced off what we have really been paid it refuses, because 1,000/item is above
        // the ceiling a 470 resale implies.
        assertFalse(Sweep.plan(book, 470, 5_000_000L, 1_000, 0, CFG).go());
    }

    @Test
    void theCheapTailIsStillSweptWhenItIsGenuinelyCheap() {
        // Same realised resale of 470, but now there is supply under the implied ceiling.
        List<Liquidity.Level> book = List.of(
                new Liquidity.Level(300, 4, 256), new Liquidity.Level(1_000, 3, 192));
        Sweep.Plan plan = Sweep.plan(book, 470, 5_000_000L, 1_000, 0, CFG);
        assertTrue(plan.go(), plan.reason());
        // Only the cheap level: the 1,000 listings are above the ceiling and must be left alone.
        assertTrue(plan.units() == 256, "expected only the 300/item level, got " + plan.units());
        assertTrue(plan.ceilingPrice() < 1_000,
                "ceiling " + plan.ceilingPrice() + " must exclude the expensive level");
    }

    @Test
    void aSweepNeedsSomeEvidenceOfResaleValue() {
        // No resale figure at all is not a licence to guess: the plan must refuse.
        List<Liquidity.Level> book = List.of(new Liquidity.Level(300, 4, 256));
        assertFalse(Sweep.plan(book, 0, 5_000_000L, 1_000, 0, CFG).go());
    }
}
