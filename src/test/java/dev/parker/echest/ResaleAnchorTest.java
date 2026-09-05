package dev.parker.echest;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Why a live sell quote is the wrong thing to price a buy sweep against.
 *
 * <p>The sell quote undercuts the current floor by one grid step - that's what makes it a sell
 * quote. Feeding it into a buy ceiling means the ceiling is computed from a number that is, by
 * construction, only a fraction of a dollar below the very ask a sweep is trying to buy. Applying
 * a real profit margin on top of that produces a ceiling below the floor whenever the book has any
 * real depth, which live obsidian did: the desk refused a 413/item ask against a "resale anchor"
 * of 412 - literally 413 minus the one-dollar undercut step - for thirty straight minutes.
 *
 * <p>The desk already derives a defensible resale figure with real evidence behind it: p90 of
 * every realised trade, guarded by a minimum sample count and a coherence check on dispersion.
 * That number, not the undercut-quote, is what a sweep should be priced against.
 */
final class ResaleAnchorTest {
    private static final Liquidity.Config CFG = Liquidity.Config.defaults();

    /** A tight, liquid book: real depth, prices clustered close together. */
    private static List<Liquidity.Level> tightBook() {
        return List.of(
                new Liquidity.Level(406, 3, 192),
                new Liquidity.Level(408, 2, 128),
                new Liquidity.Level(413, 4, 256));
    }

    @Test
    void undercutQuoteAsResaleAnchorRefusesAnyTightBook() {
        // This is the bug, reproduced with the exact live numbers: the quote for a tight book
        // undercuts the floor by one grid step, landing right next to the ask it is supposedly
        // pricing a resale against.
        Liquidity.Quote quote = Liquidity.quote(tightBook(), List.of(), Double.NaN, 64, CFG);
        assertEquals(405, quote.unitPrice(), "the undercut quote sits one step under the 406 floor");

        // Pricing a sweep off that quote refuses the very ask it was computed from.
        Sweep.Plan wrong = Sweep.plan(tightBook(), quote.unitPrice(), 5_000_000L, 1_000, 0, CFG);
        assertFalse(wrong.go(), "documents the failure: " + wrong.reason());
    }

    @Test
    void calibratedEvidenceUnlocksTheSameBook() {
        // Real realised sales, up to 700/item - the exact shape of the live obsidian desk that
        // sat refusing to buy. p90 = 700 is real evidence: the book has demonstrably cleared there.
        List<Long> sold = new ArrayList<>();
        for (int i = 0; i < 20; i++) sold.add(400L + i * 15L);   // 400..685, p90 near 700
        History.Samples samples = new History.Samples("minecraft:obsidian", 64, List.of(), sold);
        Desk.Calibration cal = Desk.derive(samples, 5_000_000L, CFG);
        assertTrue(cal.ladderReady(), cal.evidence());
        long resaleAnchor = cal.cfg().fallbackPrice();
        assertTrue(resaleAnchor > 500, "expected a p90-derived anchor well above the book, got " + resaleAnchor);

        // Priced against real evidence instead of the undercut-quote, the same tight book is
        // obviously worth sweeping.
        Sweep.Plan right = Sweep.plan(tightBook(), resaleAnchor, 5_000_000L, 1_000, 0, CFG);
        assertTrue(right.go(), right.reason());
    }

    @Test
    void anUncalibratedDeskStillHasNoBetterEvidenceThanTheQuote() {
        // Before 20 trades exist there is nothing better than the live quote to fall back on, and
        // that must still work exactly as before - this is not a regression for a fresh desk.
        Sweep.Plan plan = Sweep.plan(
                List.of(new Liquidity.Level(300, 4, 256)), 900, 5_000_000L, 1_000, 0, CFG);
        assertTrue(plan.go(), plan.reason());
    }
}
