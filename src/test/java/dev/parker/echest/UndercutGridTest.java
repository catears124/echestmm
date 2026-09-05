package dev.parker.echest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How far below a competing level an undercut goes.
 *
 * <p>The auction house snaps the <em>listing total</em> to a $100 grid, so on a 64-stack desk one
 * grid step is 100/64 of a dollar per item. Subtracting a whole tick per item took $6,400 off
 * every stack: with the book at 391/item the desk listed 291/item, selling stacks at 19,000 while
 * 25,000 was visible on screen. Nobody decided that - it was a units mismatch.
 */
final class UndercutGridTest {
    private static final Liquidity.Config CFG = Liquidity.Config.defaults();

    @Test
    void aStackUndercutsByOneGridStepNotOneTickPerItem() {
        // 391/item is 25,024 a stack. A competitive undercut is a few dollars off the total.
        long undercut = CFG.undercut(391, 64);
        assertTrue(undercut >= 389,
                "expected a per-item step of about 1.5, got " + (391 - undercut) + " per item");
        assertTrue(undercut < 391, "an undercut must actually be lower");
        // The old behaviour, kept visible. It is worse than a single step: subtracting a whole
        // tick per item and then snapping to the same coarse grid rounds down twice. A 469/item
        // stack floor became 300/item - $19,200 a stack against $30,000 on screen, which is
        // exactly the receipt that exposed this.
        assertEquals(300, CFG.undercut(469, 1));
        assertEquals(200, CFG.undercut(391, 1));
    }

    @Test
    void theGridScalesWithLotSize() {
        assertEquals(1, CFG.perItemGrid(64));     // 100/64 rounds down to a dollar
        assertEquals(6, CFG.perItemGrid(16));     // 100/16
        assertEquals(100, CFG.perItemGrid(1));    // an ender chest listing is its own total
    }

    @Test
    void aStackDeskQuotesJustUnderTheStackFloor() {
        // The whole point: with stacks trading at 391/item the quote must land next to it, not a
        // quarter under it. 19,000 versus 25,000 a stack is the difference this pins.
        List<Liquidity.Level> stacks = List.of(new Liquidity.Level(391, 12, 768));
        Liquidity.Quote quote = Liquidity.quote(stacks, List.of(), Double.NaN, 64, CFG);
        assertTrue(quote.unitPrice() >= 385,
                "expected a price close to 391/item, got " + quote.unitPrice()
                        + " (" + quote.unitPrice() * 64 + " a stack)");
        assertTrue(quote.unitPrice() < 391);
        assertTrue(quote.unitPrice() * 64 >= 24_600,
                "a stack should list near 25,000, got " + quote.unitPrice() * 64);
    }

    @Test
    void anEnderChestStillUndercutsByAFullHundred() {
        // Single-unit lots are unaffected: their listing total *is* their per-item price, so the
        // $100 grid applies directly. This is the case that always worked and must keep working.
        List<Liquidity.Level> chests = List.of(new Liquidity.Level(6_700, 4, 4));
        Liquidity.Quote quote = Liquidity.quote(chests, List.of(), Double.NaN, 1, CFG);
        assertEquals(6_600, quote.unitPrice());
    }
}
