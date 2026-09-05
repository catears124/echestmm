package dev.parker.echest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mixed stack sizes, which is what actually broke the obsidian desk.
 *
 * <p>The book was advertised in 1x, 8x, 16x and 64x lots. Comparing only listings whose stack size
 * equalled the desk's listing unit made a busy book read as {@code 0 listings of 64x}, so the bid
 * ladder had no floor to price against and never placed a single bid in a whole session.
 */
final class MixedStackBookTest {
    private static final Liquidity.Config CFG = Liquidity.Config.defaults();

    /** A book of 3,000/item in a 64-lot, 4,000/item in a 16-lot, and 5,000/item in singles. */
    private static List<Liquidity.Level> mixedBook() {
        return List.of(
                new Liquidity.Level(3_000, 1, 64),
                new Liquidity.Level(4_000, 1, 16),
                new Liquidity.Level(5_000, 2, 2));
    }

    @Test
    void theCheapestPerItemIsTheFloorRegardlessOfLotSize() {
        Liquidity.Quote q = Liquidity.quote(mixedBook(), List.of(), Double.NaN, CFG);
        // Floor is 3,000/item from the 64-lot, so the undercut is one tick under that.
        assertEquals(CFG.undercut(3_000), q.unitPrice());
    }

    @Test
    void queuePositionCountsListingsNotItems() {
        // Three listings sit under 5,000/item: the 64-lot and the 16-lot.
        assertEquals(2, Liquidity.queueAhead(mixedBook(), List.of(), 5_000));
        assertEquals(1, Liquidity.queueAhead(mixedBook(), List.of(), 4_000));
        assertEquals(0, Liquidity.queueAhead(mixedBook(), List.of(), 3_000));
    }

    @Test
    void aBuyoutCostsUnitPriceTimesItemsNotTimesListings() {
        // Clearing everything under 5,000/item means 64 items at 3,000 plus 16 at 4,000 = 256,000.
        Liquidity.LiftPlan plan = Liquidity.planFloorLift(mixedBook(), 5_000, 0, 18, 10_000_000, CFG);
        assertEquals(256_000, plan.cost());
        assertEquals(80, plan.units());
        assertTrue(plan.reason().contains("80 items"));
    }

    @Test
    void aThinCashBudgetStillBlocksAnExpensiveBuyout() {
        assertFalse(Liquidity.planFloorLift(mixedBook(), 5_000, 0, 18, 100_000, CFG).go());
    }

    @Test
    void theLadderRefusesRatherThanBidInsideItsProfitMargin() {
        // Ask 10,000/item with a 30% guard caps the bid at 7,000; a 9,000 start cannot be honoured.
        Liquidity.BidPlan plan = Liquidity.ladderBid(0, 0, 10_000, true, false,
                9_000, 100, 0.30, CFG);
        assertFalse(plan.buy());
        assertTrue(plan.reason().contains("cannot support"), plan.reason());
    }

    @Test
    void aTightCeilingShrinksTheStepInsteadOfCollapsingTheLadder() {
        // Room is 2,000 with a 5,000 step: the step shrinks so the ladder still walks.
        long bid = Liquidity.ladderBid(0, 12_000, 20_000, false, false, 10_000, 5_000, 0.10, CFG).bid();
        assertEquals(10_000, bid);
        long next = Liquidity.ladderBid(bid, 12_000, 20_000, true, false, 10_000, 5_000, 0.10, CFG).bid();
        assertTrue(next > bid && next <= 12_000, "expected a step inside the room, got " + next);
    }
}
