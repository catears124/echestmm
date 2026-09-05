package dev.parker.echest;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The listing minimum, which used to be a memory of past prices instead of a loss guard.
 *
 * <p>Five hundred realised ender chest fills put {@code minPrice} at p25 = 6,100. A book floor of
 * 5,900 then could not be undercut, so every listing joined the back of the queue at 6,100+, fills
 * slowed, and the slow fills fed the next calibration - a floor holding itself up. The minimum's
 * real job is refusing to give inventory away, so it belongs far below the trading range.
 */
final class ListingFloorTest {
    private static final Liquidity.Config CFG = Liquidity.Config.defaults();

    private static History.Samples echestSales() {
        // A realistic ender chest series: p25 near 6,100, median near 6,400.
        List<Long> sold = new ArrayList<>();
        for (int i = 0; i < 100; i++) sold.add(5_800L + (i % 6) * 200L);
        return new History.Samples("minecraft:ender_chest", 1, List.of(), sold);
    }

    @Test
    void theMinimumLeavesRoomToCompeteWithTheLiveBook() {
        Desk.Calibration cal = Desk.derive(echestSales(), 5_000_000L, CFG);
        assertTrue(cal.ladderReady(), cal.evidence());
        long median = 6_400;
        assertTrue(cal.cfg().minPrice() < 5_900,
                "minimum " + cal.cfg().minPrice() + " must allow undercutting a 5,900 floor");
        assertTrue(cal.cfg().minPrice() < median / 2,
                "minimum " + cal.cfg().minPrice() + " should sit far below the " + median + " median");
        // It is still a real guard: it must refuse to give a 6,400 item away for pennies.
        assertTrue(cal.cfg().minPrice() > 100, "minimum " + cal.cfg().minPrice() + " is not a guard at all");
    }

    @Test
    void undercuttingAFallingBookIsNoLongerBlocked() {
        Desk.Calibration cal = Desk.derive(echestSales(), 5_000_000L, CFG);
        // The book has fallen to 5,900 and we hold nothing at a cheaper price.
        List<Liquidity.Level> book = List.of(new Liquidity.Level(5_900, 4, 4));
        Liquidity.Quote quote = Liquidity.quote(book, List.of(), Double.NaN, 1, cal.cfg());
        assertTrue(quote.unitPrice() < 5_900,
                "expected an undercut of 5,900, got " + quote.unitPrice());
        assertTrue(quote.reason().contains("undercutting floor 5900"), quote.reason());
    }

    @Test
    void aBindingMinimumSaysSoInsteadOfClaimingToUndercut() {
        // A desk whose minimum genuinely binds must report the price it is really listing at.
        Liquidity.Config strict = new Liquidity.Config(6_100, CFG.fallbackPrice(), CFG.tick(),
                CFG.maxHoldSeconds(), CFG.ladderStep(), CFG.snipeMarginPct(), CFG.liftMarginPct(),
                CFG.maxLiftUnits(), CFG.maxCashSharePct());
        List<Liquidity.Level> book = List.of(new Liquidity.Level(5_900, 4, 4));
        Liquidity.Quote quote = Liquidity.quote(book, List.of(), Double.NaN, 1, strict);
        assertEquals(6_100, quote.unitPrice());
        assertTrue(quote.reason().contains("listing minimum binds"), quote.reason());
        assertTrue(quote.reason().contains("wanted"), quote.reason());
    }
}
