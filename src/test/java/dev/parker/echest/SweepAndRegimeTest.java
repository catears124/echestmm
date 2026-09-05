package dev.parker.echest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order-sweep planner and the three sell regimes.
 *
 * <p>Both encode the same observation from live trading: a buy order fills cheapest-first up to a
 * ceiling, so acquisition is one priced decision rather than a race, and the sell price depends on
 * how much company we have rather than on a single undercut rule.
 */
final class SweepAndRegimeTest {
    private static final Liquidity.Config CFG = Liquidity.Config.defaults();

    private static Liquidity.Level level(long unitPrice, int listings, int units) {
        return new Liquidity.Level(unitPrice, listings, units);
    }

    @Test
    void costsTheBookCheapestFirstRatherThanAtTheCeiling() {
        // 100 items at 300, 100 at 350, 100 at 390. Resale 500 with the default snipe margin.
        List<Liquidity.Level> book = List.of(
                level(300, 2, 100), level(350, 2, 100), level(390, 2, 100));
        Sweep.Plan plan = Sweep.plan(book, 500, 5_000_000L, 1_000, 0, CFG);
        assertTrue(plan.go(), plan.reason());
        // Everything under the ceiling is taken, and costed at its own level.
        assertEquals(300 * 100 + 350 * 100 + 390 * 100, plan.modelledCost());
        assertEquals(300, plan.units());
        // The realised average is far below the ceiling: that is the whole point of best-first fill.
        assertTrue(plan.averageCost() < plan.ceilingPrice(),
                "average " + plan.averageCost() + " should beat ceiling " + plan.ceilingPrice());
    }

    @Test
    void refusesAsksAboveTheMarginCeiling() {
        // Resale 500 implies a ceiling below 500; a book at 490 is not worth sweeping.
        List<Liquidity.Level> book = List.of(level(490, 3, 200));
        Sweep.Plan plan = Sweep.plan(book, 500, 5_000_000L, 1_000, 0, CFG);
        assertFalse(plan.go());
        assertTrue(plan.reason().contains("ceiling"), plan.reason());
    }

    @Test
    void stopsAtTheCashShareAndPartiallyFillsTheLastLevel() {
        List<Liquidity.Level> book = List.of(level(100, 10, 10_000));
        // A small balance must produce a small order, not a refusal.
        Sweep.Plan plan = Sweep.plan(book, 1_000, 10_000, 10_000, 0, CFG);
        assertTrue(plan.go(), plan.reason());
        long budget = (long) (10_000 * CFG.maxCashSharePct());
        assertTrue(plan.modelledCost() <= budget,
                "cost " + plan.modelledCost() + " must stay inside budget " + budget);
        assertTrue(plan.units() < 10_000);
    }

    @Test
    void stopsAtTheHoldCap() {
        List<Liquidity.Level> book = List.of(level(100, 10, 10_000));
        Sweep.Plan plan = Sweep.plan(book, 1_000, 100_000_000L, 64, 0, CFG);
        assertTrue(plan.go(), plan.reason());
        assertEquals(64, plan.units());
    }

    @Test
    void namesTheCeilingAtTheLastLevelItWants() {
        // Nothing above 350 should be bought, so the order must not be priced at the full ceiling -
        // an order priced higher keeps filling into asks we already rejected.
        List<Liquidity.Level> book = List.of(level(300, 1, 50), level(350, 1, 50));
        Sweep.Plan plan = Sweep.plan(book, 1_000, 5_000_000L, 1_000, 0, CFG);
        assertTrue(plan.go(), plan.reason());
        assertEquals(350, plan.ceilingPrice());
    }

    @Test
    void refusesWithoutCashRoomOrSupply() {
        List<Liquidity.Level> book = List.of(level(100, 1, 64));
        assertFalse(Sweep.plan(book, 1_000, 0, 64, 0, CFG).go());
        assertFalse(Sweep.plan(book, 1_000, 5_000_000L, 0, 0, CFG).go());
        assertFalse(Sweep.plan(List.of(), 1_000, 5_000_000L, 64, 0, CFG).go());
        assertFalse(Sweep.plan(book, 0, 5_000_000L, 64, 0, CFG).go());
    }

    @Test
    void refusesAnOrderThatCannotClearTheProfitFloor() {
        // Real money: the sweep must be worth the capital it locks up, not merely positive.
        List<Liquidity.Level> book = List.of(level(300, 1, 64));
        assertTrue(Sweep.plan(book, 500, 5_000_000L, 64, 5_000, CFG).go());
        assertFalse(Sweep.plan(book, 500, 5_000_000L, 64, 500_000, CFG).go());
    }

    @Test
    void buysBelowTheListingFloorBecauseThatFloorIsASellSideRule() {
        // cfg.minPrice is the cheapest we will ever list at. It must not stop us buying at 300.
        List<Liquidity.Level> book = List.of(level(300, 2, 128));
        assertTrue(CFG.minPrice() > 300, "fixture only meaningful below the listing floor");
        assertTrue(Sweep.plan(book, 900, 5_000_000L, 128, 0, CFG).go());
    }

    @Test
    void classifiesTheThreeRegimesByCompetingListings() {
        assertEquals(Liquidity.Regime.OPEN, Liquidity.classify(List.of()));
        assertEquals(Liquidity.Regime.THIN, Liquidity.classify(List.of(level(6_000, 1, 64))));
        assertEquals(Liquidity.Regime.THIN,
                Liquidity.classify(List.of(level(6_000, 3, 192), level(6_100, 2, 128))));
        assertEquals(Liquidity.Regime.CROWDED,
                Liquidity.classify(List.of(level(6_000, 40, 2_560))));
        // The boundary is the buy-out affordability line, so it is pinned.
        assertEquals(Liquidity.Regime.THIN,
                Liquidity.classify(List.of(level(6_000, Liquidity.THIN_BOOK_LISTINGS - 1, 64))));
        assertEquals(Liquidity.Regime.CROWDED,
                Liquidity.classify(List.of(level(6_000, Liquidity.THIN_BOOK_LISTINGS, 64))));
    }

    @Test
    void neverUndercutsOurOwnRestingAsk() {
        // We rest at 6,800. The book collapses to 5,000. Undercutting 5,000 would win the sale
        // from ourselves at a $1,800 discount we chose to hand over.
        List<Liquidity.Level> book = List.of(level(5_000, 2, 128));
        Liquidity.Quote quote = Liquidity.quote(book, List.of(6_800L), 0.5, CFG);
        assertEquals(6_800, quote.unitPrice());
        assertTrue(quote.reason().contains("undercutting ourselves"), quote.reason());
    }

    @Test
    void stillUndercutsCompetitorsAboveOurLevel() {
        // Our own ask is cheap, so there is nothing to protect: undercut normally.
        List<Liquidity.Level> book = List.of(level(9_000, 2, 128));
        Liquidity.Quote quote = Liquidity.quote(book, List.of(1_000L), 0.5, CFG);
        assertTrue(quote.unitPrice() < 9_000, "expected an undercut, got " + quote.unitPrice());
        assertFalse(quote.reason().contains("undercutting ourselves"), quote.reason());
    }
}
