package dev.parker.echest;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guards that stop a desk from losing money by construction.
 *
 * <p>Both come from one live failure. The obsidian desk derived
 * {@code p10 300 ... p90 38000, IQR/median 5425%} by mixing per-item buys with sale receipts
 * logged before the unit fix, and concluded {@code min sell 300} while the order side was paying
 * 350 per item. Two independent things were missing: a refusal to calibrate from incoherent
 * history, and a floor under the ask that knows what the inventory cost.
 */
final class RiskGuardTest {
    private static final Liquidity.Config CFG = Liquidity.Config.defaults();

    @Test
    void refusesToCalibrateFromTwoMarketsPretendingToBeOne() {
        // The real distribution: hundreds of per-item buys near 300-400, plus mis-parsed stack
        // receipts near 22,000-38,000.
        List<Long> bought = new ArrayList<>();
        for (int i = 0; i < 200; i++) bought.add(300L + (i % 2) * 100L);
        List<Long> sold = new ArrayList<>();
        for (int i = 0; i < 100; i++) sold.add(22_000L + (i % 3) * 8_000L);
        History.Samples poisoned = new History.Samples("minecraft:obsidian", 64, bought, sold);

        Desk.Calibration cal = Desk.derive(poisoned, 5_000_000L, CFG);
        assertFalse(cal.ladderReady(), cal.evidence());
        assertTrue(cal.evidence().contains("two markets"), cal.evidence());
        // And it must not hand back a derived minimum at all: the config is left untouched.
        assertEquals(CFG.minPrice(), cal.cfg().minPrice());
    }

    @Test
    void stillCalibratesFromACoherentBook() {
        List<Long> sold = new ArrayList<>();
        for (int i = 0; i < 60; i++) sold.add(5_800L + (i % 6) * 200L);
        History.Samples clean = new History.Samples("minecraft:ender_chest", 1, List.of(), sold);
        Desk.Calibration cal = Desk.derive(clean, 5_000_000L, CFG);
        assertTrue(cal.ladderReady(), cal.evidence());
    }

    @Test
    void theCostFloorSitsAboveWhatWePaid() {
        // Paid 350/item. With the default snipe margin the ask must clear cost plus that margin.
        long floor = Liquidity.costFloor(350, CFG.snipeMarginPct());
        assertTrue(floor > 350, "floor " + floor + " must exceed the 350 paid");
        assertEquals(Math.ceil(350 * (1.0 + CFG.snipeMarginPct())), floor, 1.0);
    }

    @Test
    void theCostFloorIsSilentWhenNothingWasPaid() {
        // Nothing acquired yet means no basis to defend, and no excuse to invent a price.
        assertEquals(0, Liquidity.costFloor(0, CFG.snipeMarginPct()));
        assertEquals(0, Liquidity.costFloor(-1, CFG.snipeMarginPct()));
        // A missing margin must not turn into a NaN price.
        assertEquals(350, Liquidity.costFloor(350, Double.NaN));
        assertEquals(350, Liquidity.costFloor(350, 0.0));
    }

    @Test
    void anOrderCostCeilingBoundsWhatOneSweepCommits() {
        // Deep cheap supply plus a big balance: the sweep is limited by the risk cap, not the book.
        List<Liquidity.Level> book = List.of(new Liquidity.Level(300, 200, 20_000));
        long maxOrder = 1_000_000L;
        long orderCash = Math.min(50_000_000L, Math.round(maxOrder / CFG.maxCashSharePct()));
        Sweep.Plan plan = Sweep.plan(book, 900, orderCash, 20_000, 0, CFG);
        assertTrue(plan.go(), plan.reason());
        assertTrue(plan.modelledCost() <= maxOrder,
                "cost " + plan.modelledCost() + " must respect the " + maxOrder + " cap");
    }
}
