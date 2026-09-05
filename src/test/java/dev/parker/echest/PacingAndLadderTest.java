package dev.parker.echest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pacing ceiling and the bid ladder, both of which exist because of measured behaviour: five
 * kicks at a sustained 2.0-2.2 actions/second, and $11.8M of obsidian bought at a median 32.9k
 * while the same book was trading at 18-25k earlier in the session.
 */
final class PacingAndLadderTest {
    private static final Liquidity.Config CFG = Liquidity.Config.defaults();

    @Test
    void aPenaltyDropsTheCeilingAndRecoveryIsSlower() {
        assertEquals(0.65, ActionPacer.penaltyOf(0.90), 1e-9);
        assertEquals(0.95, ActionPacer.recoveryOf(0.90), 1e-9);
        // One kick costs five clean recovery intervals: the ratchet is deliberately asymmetric.
        assertTrue(ActionPacer.recoveryOf(0.90) - 0.90 < 0.90 - ActionPacer.penaltyOf(0.90));
    }
    @Test
    void theCeilingStaysInsideTheSafeBand() {
        assertEquals(0.40, ActionPacer.clamp(0.01), 1e-9);
        assertEquals(1.30, ActionPacer.clamp(9.0), 1e-9);
        assertEquals(0.90, ActionPacer.clamp(Double.NaN), 1e-9);
        // The observed kick threshold must sit above whatever the pacer can ever allow.
        assertTrue(ActionPacer.clamp(9.0) < 2.0);
    }

    @Test
    void penaltiesCannotFallThroughTheFloor() {
        double cap = 0.90;
        for (int i = 0; i < 20; i++) cap = ActionPacer.penaltyOf(cap);
        assertEquals(0.40, cap, 1e-9);
    }
    @Test
    void quantizingMatchesTheServerRoundingThatBrokeFillMeasurement() {
        // Asking 5,599 listed at 5,500, so the sale receipt never matched the requested price.
        assertEquals(5_500, Liquidity.quantize(5_599, 100));
        assertEquals(5_500, Liquidity.quantize(5_500, 100));
        assertEquals(5_599, Liquidity.quantize(5_599, 1));
        assertEquals(5_500, CFG.undercut(5_600));
        // An off-grid competitor snaps down a full tick below its own grid cell.
        assertEquals(5_400, CFG.undercut(5_599));
    }

    @Test
    void theLadderStartsLowAndConcedesOnlyWhenNothingFills() {
        Liquidity.BidPlan first = Liquidity.ladderBid(0, 0, 40_000, false, false,
                25_000, 500, 0.25, CFG);
        assertTrue(first.buy());
        assertEquals(25_000, first.bid());

        Liquidity.BidPlan held = Liquidity.ladderBid(25_000, 0, 40_000, false, false,
                25_000, 500, 0.25, CFG);
        assertEquals(25_000, held.bid());

        Liquidity.BidPlan conceded = Liquidity.ladderBid(25_000, 0, 40_000, true, false,
                25_000, 500, 0.25, CFG);
        assertEquals(25_500, conceded.bid());
    }

    @Test
    void aFillWalksTheBidBackDown() {
        Liquidity.BidPlan afterFill = Liquidity.ladderBid(27_000, 0, 40_000, false, true,
                25_000, 500, 0.25, CFG);
        assertEquals(26_500, afterFill.bid());
        // Never below the configured start.
        assertEquals(25_000, Liquidity.ladderBid(25_000, 0, 40_000, false, true,
                25_000, 500, 0.25, CFG).bid());
    }

    @Test
    void theLadderNeverPaysThroughTheSpreadCeiling() {
        // 40k ask with a 25% spread caps the bid at 30k, however long nothing fills.
        long bid = 25_000;
        for (int i = 0; i < 50; i++) {
            bid = Liquidity.ladderBid(bid, 0, 40_000, true, false, 25_000, 500, 0.25, CFG).bid();
        }
        assertEquals(30_000, bid);
    }

    @Test
    void theLadderRefusesWhenTheStartIsAlreadyThroughTheCeiling() {
        Liquidity.BidPlan plan = Liquidity.ladderBid(0, 0, 30_000, true, false,
                25_000, 500, 0.25, CFG);
        assertFalse(plan.buy());
        assertTrue(plan.reason().contains("ceiling"));
    }

    @Test
    void theLadderNeedsAnAskToPriceAgainst() {
        assertFalse(Liquidity.ladderBid(0, 0, 0, true, false, 25_000, 500, 0.25, CFG).buy());
    }

    @Test
    void ladderBidsSitOnTheTickGrid() {
        Liquidity.BidPlan plan = Liquidity.ladderBid(25_050, 0, 40_000, true, false,
                25_000, 550, 0.25, CFG);
        assertEquals(0, plan.bid() % CFG.tick());
    }
}
