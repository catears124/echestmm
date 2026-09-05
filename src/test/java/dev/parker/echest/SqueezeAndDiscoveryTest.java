package dev.parker.echest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The squeeze - post asks above the book, then take everything under them - and upward price
 * discovery for the case where nobody else is selling.
 *
 * <p>Both exist because of measured behaviour. Ender chests were filling in 2-11 seconds at 6,800,
 * which is not a price, it is a queue: past sales said 6,800 only because that is all we ever
 * asked. And the obsidian book is thin enough that clearing the tail under a chosen level costs
 * less than the uplift it creates - provided demand can absorb the inventory, which is the test
 * that separates a squeeze from an expensive way to buy obsidian.
 */
final class SqueezeAndDiscoveryTest {
    private static final Liquidity.Config CFG = Liquidity.Config.defaults();

    private static Liquidity.Config cfg(long min, long fallback) {
        return new Liquidity.Config(min, fallback, 100, 900.0, 500, 0.15, 0.25, 12, 0.20);
    }

    // ---- price discovery -------------------------------------------------------------------

    @Test
    void instantFillsRatchetTheAskUpward() {
        Liquidity.Config c = cfg(1_000, 6_800);
        long ask = Liquidity.discoveryAsk(0, 2.0, 2.0, 6_800, 100_000, c);
        assertTrue(ask > 6_800, "a 2s fill means underpriced, got " + ask);
        // Compounding: keep filling instantly and the ask keeps climbing.
        long next = Liquidity.discoveryAsk(ask, 2.0, 2.0, 6_800, 100_000, c);
        assertTrue(next > ask);
        long far = ask;
        for (int i = 0; i < 20; i++) far = Liquidity.discoveryAsk(far, 1.0, 1.0, 6_800, 100_000, c);
        assertTrue(far > 30_000, "twenty instant fills should discover well past 30k, got " + far);
    }

    @Test
    void silenceWalksTheAskBackDown() {
        Liquidity.Config c = cfg(1_000, 20_000);
        long lowered = Liquidity.discoveryAsk(20_000, Double.NaN, 300.0, 20_000, 100_000, c);
        assertTrue(lowered < 20_000, "no fill for five minutes should reduce the ask");
    }

    @Test
    void discoveryRespectsTheFloorAndTheSanityCeiling() {
        Liquidity.Config c = cfg(5_000, 6_000);
        assertEquals(5_000, Liquidity.discoveryAsk(5_000, Double.NaN, 10_000.0, 6_000, 100_000, c));
        assertEquals(9_000, Liquidity.discoveryAsk(9_000, 1.0, 1.0, 6_000, 9_000, c));
    }

    @Test
    void aSlowFillNeitherRaisesNorLowers() {
        Liquidity.Config c = cfg(1_000, 20_000);
        assertEquals(20_000, Liquidity.discoveryAsk(20_000, 40.0, 40.0, 20_000, 100_000, c));
    }

    // ---- the squeeze -----------------------------------------------------------------------

    /** A thin book: 4 stacks at 300/item, one at 320, one at 400. */
    private static List<Liquidity.Level> thinBook() {
        return List.of(
                new Liquidity.Level(300, 4, 256),
                new Liquidity.Level(320, 1, 64),
                new Liquidity.Level(400, 1, 64));
    }

    @Test
    void postsAboveTheBookAndSweepsEverythingUnderIt() {
        Campaign.Plan plan = Campaign.plan(thinBook(), 128, 250, 18, 64,
                50_000_000, 0.5, 100_000, cfg(100, 400));
        assertTrue(plan.go(), plan.reason());
        assertTrue(plan.askUnitPrice() > 300, "the ask must sit above the floor");
        assertEquals(plan.askUnitPrice() - 100, plan.sweepCeiling());
        assertTrue(plan.sweepUnits() > 0);
        assertTrue(plan.expectedProfit() > 0);
        assertTrue(plan.reason().contains("clear"), plan.reason());
    }

    @Test
    void refusesWithoutMeasuredDemand() {
        Campaign.Plan unmeasured = Campaign.plan(thinBook(), 128, 250, 18, 64,
                50_000_000, Double.NaN, 100_000, cfg(100, 400));
        assertFalse(unmeasured.go());
        assertTrue(unmeasured.reason().contains("unmeasured"), unmeasured.reason());

        // A book that absorbs one item every two minutes cannot take 128 of them.
        Campaign.Plan tooSlow = Campaign.plan(thinBook(), 128, 250, 18, 64,
                50_000_000, 0.008, 100_000, cfg(100, 400));
        assertFalse(tooSlow.go());
    }

    @Test
    void refusesWhenTheSweepExceedsTheCashShare() {
        // 20% of $100,000 is $20,000; clearing 256 items at 300 costs $76,800.
        Campaign.Plan plan = Campaign.plan(thinBook(), 128, 250, 18, 64,
                100_000, 0.5, 100_000, cfg(100, 400));
        assertFalse(plan.go(), plan.reason());
    }

    @Test
    void refusesWithNoInventoryOrNoSlots() {
        assertFalse(Campaign.plan(thinBook(), 0, 250, 18, 64, 50_000_000, 0.5, 100_000, CFG).go());
        assertFalse(Campaign.plan(thinBook(), 128, 250, 0, 64, 50_000_000, 0.5, 100_000, CFG).go());
    }

    @Test
    void neverPostsAboveTheSanityCeiling() {
        Campaign.Plan plan = Campaign.plan(thinBook(), 128, 250, 18, 64,
                50_000_000, 0.5, 310, cfg(100, 400));
        assertTrue(!plan.go() || plan.askUnitPrice() <= 310, plan.reason());
    }

    @Test
    void anEmptyBookIsPriceDiscoveryNotASqueeze() {
        Campaign.Plan plan = Campaign.plan(List.of(), 128, 250, 18, 64,
                50_000_000, 0.5, 100_000, CFG);
        assertFalse(plan.go());
        assertTrue(plan.reason().contains("price discovery"), plan.reason());
    }

    @Test
    void theSweepCostIsItemsNotListings() {
        Campaign.Plan plan = Campaign.plan(thinBook(), 128, 250, 18, 64,
                50_000_000, 0.5, 100_000, cfg(100, 400));
        assertTrue(plan.go(), plan.reason());
        // Everything under the ask, priced per item and multiplied by the items in each lot.
        long expected = 0;
        int units = 0;
        for (Liquidity.Level l : thinBook()) {
            if (l.unitPrice() >= plan.askUnitPrice()) break;
            expected += (long) l.unitPrice() * l.units();
            units += l.units();
        }
        assertEquals(expected, plan.sweepCost());
        assertEquals(units, plan.sweepUnits());
    }
}
