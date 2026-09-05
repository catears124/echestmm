package dev.parker.echest;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Desk levels must be statistics of realised trades, not constants.
 *
 * <p>The fixtures below are the real distribution from this account's log: 384 obsidian stack buys
 * with p10 20,000, p25 25,150, median 32,900, p75 35,700, p90 38,500.
 */
final class DeskCalibrationTest {
    private static final Liquidity.Config BASE = Liquidity.Config.defaults();

    private static List<String> obsidianLog() {
        // A distribution with the same shape as the logged one, on the 100 grid.
        long[] prices = {
                18_500, 19_000, 20_000, 20_500, 20_600, 21_000, 22_600, 24_800, 24_900, 25_000,
                25_100, 25_200, 26_000, 28_000, 30_000, 32_000, 32_900, 33_000, 33_000, 33_800,
                34_000, 35_000, 35_700, 36_000, 37_000, 38_000, 38_500, 39_000, 40_000, 44_900
        };
        List<String> lines = new ArrayList<>();
        for (long p : prices) {
            lines.add("2026-09-05T08:00:00Z [receipt] BUY 64x Obsidian @ " + p);
        }
        return lines;
    }

    @Test
    void parsesOnlyReceiptsForTheDeskItemAndUnitSize() {
        assertNull(History.parseReceipt("2026-09-05T08:00:00Z [receipt] BUY 64x Obsidian @ 20000",
                "ender_chest", 1));
        assertNull(History.parseReceipt("2026-09-05T08:00:00Z [receipt] BUY 1x Obsidian @ 400",
                "obsidian", 64));
        // A LIST receipt is an intention, not a fill.
        assertNull(History.parseReceipt("2026-09-05T08:00:00Z [receipt] LIST 64x Obsidian @ 40000",
                "obsidian", 64));
        assertNull(History.parseReceipt("2026-09-05T08:00:00Z [engine] listed at 40000", "obsidian", 64));

        long[] buy = History.parseReceipt("2026-09-05T08:00:00Z [receipt] BUY 64x Obsidian @ 20000",
                "obsidian", 64);
        assertEquals(0, buy[0]);
        assertEquals(20_000, buy[1]);

        long[] sell = History.parseReceipt(
                "2026-09-05T08:46:23Z [receipt] SELL 1x Ender Chest @ 6200 (DillyBilly2000)",
                "ender_chest", 1);
        assertEquals(1, sell[0]);
        assertEquals(6_200, sell[1]);
    }


    @Test
    void aSaleWithNoStatedQuantityCountsForAnyUnitSize() {
        // Donut logs stack sales without a count: "X bought your Obsidian for $22K". Treating that
        // as 1x made the 64-unit desk report "384 bought, 0 sold" and throw its own sales away.
        long[] stackDesk = History.parseReceipt("x [receipt] SELL 0x Obsidian @ 22000", "obsidian", 64);
        assertEquals(1, stackDesk[0]);
        assertEquals(22_000, stackDesk[1]);

        long[] unitDesk = History.parseReceipt("x [receipt] SELL 0x Obsidian @ 22000", "obsidian", 1);
        assertEquals(22_000, unitDesk[1]);

        // An explicit count that disagrees with the desk is still rejected.
        assertNull(History.parseReceipt("x [receipt] SELL 1x Obsidian @ 22000", "obsidian", 64));
    }
    @Test
    void separatesBoughtFromSoldPrices() {
        List<String> lines = List.of(
                "x [receipt] BUY 64x Obsidian @ 20000",
                "x [receipt] SELL 64x Obsidian @ 40000",
                "x [receipt] BUY 64x Obsidian @ 21000");
        History.Samples s = History.collect(lines, "minecraft:obsidian", 64, 500);
        assertEquals(List.of(20_000L, 21_000L), s.bought());
        assertEquals(List.of(40_000L), s.sold());
        assertEquals(3, s.size());
    }

    @Test
    void quantilesInterpolateAndHandleDegenerateInput() {
        assertEquals(Long.MIN_VALUE, History.quantile(List.of(), 0.5));
        assertEquals(7L, History.quantile(List.of(7L), 0.9));
        assertEquals(15L, History.quantile(List.of(10L, 20L), 0.5));
        assertEquals(30L, History.quantile(List.of(10L, 20L, 30L, 40L), 0.6667), 0);
    }

    @Test
    void refusesToBidOnTooFewSamples() {
        History.Samples thin = History.collect(obsidianLog().subList(0, 5), "minecraft:obsidian", 64, 500);
        Desk.Calibration cal = Desk.derive(thin, 5_000_000, BASE);
        assertFalse(cal.ladderReady());
        assertEquals(0, cal.bidStart());
        assertTrue(cal.evidence().contains("need " + Desk.MIN_SAMPLES));
        // The base configuration is left untouched rather than replaced by guesses.
        assertEquals(BASE.minPrice(), cal.cfg().minPrice());
    }

    @Test
    void derivesEveryLevelFromTheRealisedDistribution() {
        History.Samples s = History.collect(obsidianLog(), "minecraft:obsidian", 64, 500);
        Desk.Calibration cal = Desk.derive(s, 5_000_000, BASE);
        assertTrue(cal.ladderReady(), cal.evidence());

        long p10 = Liquidity.quantize(History.quantile(s.all(), 0.10), 100);
        long p25 = Liquidity.quantize(History.quantile(s.all(), 0.25), 100);
        long p90 = Liquidity.quantize(History.quantile(s.all(), 0.90), 100);

        assertEquals(p10, cal.bidStart());                     // ladder starts at the cheap end
        assertEquals(p25, cal.cfg().minPrice());               // evidence-based sell floor
        assertEquals(p90, cal.cfg().fallbackPrice());          // empty-book ask
        assertTrue(cal.bidStep() >= 100);
        assertEquals(0, cal.bidStep() % 100);                  // on the grid
        assertTrue(cal.minSpreadPct() > 0.08 && cal.minSpreadPct() <= 0.45);
        assertTrue(cal.evidence().contains("realised trades"));
    }

    @Test
    void aWiderBookDemandsAWiderSpread() {
        List<String> tight = new ArrayList<>();
        List<String> wide = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            tight.add("x [receipt] BUY 64x Obsidian @ " + (30_000 + (i % 4) * 100));
            wide.add("x [receipt] BUY 64x Obsidian @ " + (20_000 + (i % 4) * 5_000));
        }
        double tightSpread = Desk.derive(History.collect(tight, "minecraft:obsidian", 64, 500),
                5_000_000, BASE).minSpreadPct();
        double wideSpread = Desk.derive(History.collect(wide, "minecraft:obsidian", 64, 500),
                5_000_000, BASE).minSpreadPct();
        assertTrue(wideSpread > tightSpread, wideSpread + " should exceed " + tightSpread);
    }

    @Test
    void theHoldCapFollowsTheBankrollAndIsUnsetWhenCashIsUnknown() {
        History.Samples s = History.collect(obsidianLog(), "minecraft:obsidian", 64, 500);
        int rich = Desk.derive(s, 20_000_000, BASE).maxHoldUnits();
        int poor = Desk.derive(s, 2_000_000, BASE).maxHoldUnits();
        assertTrue(rich > poor, rich + " should exceed " + poor);

        Desk.Calibration unknown = Desk.derive(s, -1, BASE);
        assertEquals(0, unknown.maxHoldUnits());
        assertTrue(unknown.evidence().contains("uncapped until /bal is known"));
    }

    @Test
    void theLadderCeilingDerivedHereStaysUnderTheDerivedAsk() {
        History.Samples s = History.collect(obsidianLog(), "minecraft:obsidian", 64, 500);
        Desk.Calibration cal = Desk.derive(s, 5_000_000, BASE);
        long ask = cal.cfg().fallbackPrice();
        long bid = cal.bidStart();
        for (int i = 0; i < 100; i++) {
            bid = Liquidity.ladderBid(bid, cal.bidCeiling(), ask, true, false, cal.bidStart(),
                    cal.bidStep(), cal.minSpreadPct(), cal.cfg()).bid();
        }
        assertTrue(bid < ask, "ladder ceiling " + bid + " must stay under the ask " + ask);
        assertTrue(bid <= Math.floor(ask * (1.0 - cal.minSpreadPct())) + 100);
    }
}
