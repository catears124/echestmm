package dev.parker.echest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LiquidityTest {
    private static final Liquidity.Config CFG = Liquidity.Config.defaults();

    private static List<Liquidity.Level> book(long... prices) {
        return java.util.Arrays.stream(prices).mapToObj(p -> new Liquidity.Level(p, 1, 1)).toList();
    }

    @Test
    void clearRateComesFromQueueDepthOverHoldTime() {
        // Sold from behind 4 competitors in 10 s: five units cleared, so 0.5 units/second.
        assertEquals(0.5, Liquidity.clearRate(List.of(new Liquidity.Fill(4, 10.0))), 1e-9);
    }

    @Test
    void clearRateIgnoresUnusableSamplesAndIsUnknownWithoutFills() {
        assertTrue(Double.isNaN(Liquidity.clearRate(List.of())));
        assertTrue(Double.isNaN(Liquidity.clearRate(List.of(new Liquidity.Fill(3, 0.0)))));
        assertEquals(1.0, Liquidity.clearRate(List.of(new Liquidity.Fill(3, 0.0), new Liquidity.Fill(0, 1.0))), 1e-9);
    }

    @Test
    void queueAheadCountsCompetitorsAndOurOwnCheaperListings() {
        assertEquals(3, Liquidity.queueAhead(book(1_000, 2_000, 9_000), List.of(1_500L), 5_000));
        assertEquals(0, Liquidity.queueAhead(book(6_000), List.of(7_000L), 5_000));
    }

    @Test
    void undercutsTheFloorUntilTheClearingRateIsMeasured() {
        Liquidity.Quote q = Liquidity.quote(book(20_000, 21_000), List.of(), Double.NaN, CFG);
        assertEquals(19_900, q.unitPrice());
        assertTrue(q.reason().contains("unmeasured"));
    }

    @Test
    void opensAtTheFallbackWhenNobodyElseIsListed() {
        Liquidity.Quote q = Liquidity.quote(List.of(), List.of(), 0.5, CFG);
        assertEquals(CFG.fallbackPrice(), q.unitPrice());
    }

    @Test
    void climbsTheBookOnlyWhenTheUpliftBeatsTheQueueDelay() {
        // Waiting behind one competitor doubles the time to fill, so it must at least double the
        // price. 80k over a 20k floor clears that bar; 30k over the same floor does not.
        Liquidity.Quote rich = Liquidity.quote(book(20_000, 80_000), List.of(), 2.0, CFG);
        assertEquals(79_900, rich.unitPrice());
        assertEquals(1, rich.queueAhead());

        Liquidity.Quote thin = Liquidity.quote(book(20_000, 30_000, 40_000), List.of(), 2.0, CFG);
        assertEquals(19_900, thin.unitPrice());
        assertEquals(0, thin.queueAhead());
    }

    @Test
    void staysAtTheFrontWhenLiquidityIsThin() {
        // 0.01 units/s: every extra place in the queue costs 100 s, which no uplift covers here.
        Liquidity.Quote q = Liquidity.quote(book(20_000, 20_500, 21_000), List.of(), 0.01, CFG);
        assertEquals(19_900, q.unitPrice());
        assertEquals(0, q.queueAhead());
    }

    @Test
    void respectsTheHoldBudgetOverRawRevenueRate() {
        Liquidity.Config tight = new Liquidity.Config(1_000, 25_000, 100, 30.0, 500,
                0.15, 0.25, 12, 0.20);
        // At 0.2 units/s, sitting behind 9 competitors takes 50 s: outside the 30 s budget.
        List<Liquidity.Level> deep = List.of(new Liquidity.Level(20_000, 9, 9), new Liquidity.Level(80_000, 1, 1));
        Liquidity.Quote q = Liquidity.quote(deep, List.of(), 0.2, tight);
        assertEquals(19_900, q.unitPrice());
        assertTrue(q.expectedWaitSeconds() <= tight.maxHoldSeconds());
    }

    @Test
    void neverQuotesUnderTheMinimum() {
        Liquidity.Config floored = new Liquidity.Config(20_000, 25_000, 100, 900.0, 500,
                0.15, 0.25, 12, 0.20);
        Liquidity.Quote q = Liquidity.quote(book(15_000, 16_000), List.of(), Double.NaN, floored);
        assertTrue(q.unitPrice() >= floored.minPrice());
    }

    @Test
    void snipeCeilingSitsUnderTheQuoteByTheConfiguredMargin() {
        assertEquals(17_000, Liquidity.snipeCeiling(20_000, CFG));
        assertEquals(0, Liquidity.snipeCeiling(0, CFG));
    }

    @Test
    void liftsTheFloorWhenClearingTheTailPaysForItself() {
        List<Liquidity.Level> levels = List.of(
                new Liquidity.Level(20_000, 2, 2),
                new Liquidity.Level(40_000, 5, 5));
        Liquidity.LiftPlan plan = Liquidity.planFloorLift(levels, 40_000, 20, 18, 1_000_000, CFG);
        assertTrue(plan.go(), plan.reason());
        assertEquals(2, plan.units());
        assertEquals(40_000, plan.cost());
        assertEquals(40_000, plan.newFloor());
        assertEquals(39_900, plan.buyUpTo());
        assertTrue(plan.expectedReturn() > plan.cost());
    }

    @Test
    void refusesToLiftADeepTail() {
        List<Liquidity.Level> levels = List.of(
                new Liquidity.Level(20_000, 30, 30),
                new Liquidity.Level(40_000, 1, 1));
        Liquidity.LiftPlan plan = Liquidity.planFloorLift(levels, 40_000, 20, 18, 100_000_000, CFG);
        assertFalse(plan.go());
        assertTrue(plan.reason().contains("deeper than"));
    }

    @Test
    void refusesToLiftBeyondTheCashBudget() {
        List<Liquidity.Level> levels = List.of(
                new Liquidity.Level(20_000, 2, 2),
                new Liquidity.Level(40_000, 5, 5));
        Liquidity.LiftPlan plan = Liquidity.planFloorLift(levels, 40_000, 20, 18, 100_000, CFG);
        assertFalse(plan.go());
        assertTrue(plan.reason().contains("cash budget"));
    }

    @Test
    void refusesToLiftWithNoSlotToSellInto() {
        List<Liquidity.Level> levels = List.of(new Liquidity.Level(20_000, 1, 1), new Liquidity.Level(40_000, 1, 1));
        assertFalse(Liquidity.planFloorLift(levels, 40_000, 20, 0, 1_000_000, CFG).go());
    }

    @Test
    void refusesToLiftWhenTheFloorIsAlreadyAtTheTarget() {
        List<Liquidity.Level> levels = List.of(new Liquidity.Level(40_000, 3, 3));
        Liquidity.LiftPlan plan = Liquidity.planFloorLift(levels, 40_000, 20, 18, 1_000_000, CFG);
        assertFalse(plan.go());
        assertTrue(plan.reason().contains("already at or above"));
    }
}
