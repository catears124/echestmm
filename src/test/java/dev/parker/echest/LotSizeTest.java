package dev.parker.echest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which listings count as competition, and which are merely supply.
 *
 * <p>Donut's search page sorts by <em>listing total</em>. Page 1 of "obsidian" is therefore a wall
 * of 1x and 8x lots - cheap totals, expensive per item - while the 64x stacks that actually trade
 * at 469/item sit pages deeper because 30,000 is a large total. Pricing a stack desk against that
 * page put listings at 121,600 while quickbuy sat at 30,000.
 *
 * <p>The two sides of the desk therefore compare different things: a stack we list competes only
 * with other stacks, but any cheap lot is supply worth buying.
 */
final class LotSizeTest {
    private static final Liquidity.Config CFG = Liquidity.Config.defaults();

    @Test
    void smallLotsArePricedPerItemAndAreNotCheap() {
        // A 1x obsidian at 2,000 is 2,000/item; a 64x at 30,000 is 469/item. Per-item pricing is
        // what makes them comparable at all, and it says the small lot is four times dearer.
        assertTrue(2_000 > 469);
        List<Liquidity.Level> asSeenOnPageOne = List.of(new Liquidity.Level(2_000, 45, 45));
        // Priced against that page, a stack desk quotes ~1,900/item = $121,600 a stack.
        Liquidity.Quote wrong = Liquidity.quote(asSeenOnPageOne, List.of(), Double.NaN, 64, CFG);
        assertTrue(wrong.unitPrice() > 1_000,
                "documents the mispricing: " + wrong.unitPrice() + "/item");
        // Priced against real stacks it quotes under 469/item, which is a listing that can sell.
        List<Liquidity.Level> realStacks = List.of(new Liquidity.Level(469, 12, 768));
        Liquidity.Quote right = Liquidity.quote(realStacks, List.of(), Double.NaN, 64, CFG);
        assertTrue(right.unitPrice() < 469,
                "expected an undercut of the real stack floor, got " + right.unitPrice());
    }

    @Test
    void aCheapSmallLotIsStillWorthBuying() {
        // Acquisition does not care about lot size: 300/item is 300/item whether it arrives as a
        // 17-block listing or a full stack, and best-first order fill takes it either way.
        List<Liquidity.Level> mixed = List.of(
                new Liquidity.Level(300, 3, 17), new Liquidity.Level(469, 12, 768));
        Sweep.Plan plan = Sweep.plan(mixed, 900, 5_000_000L, 2_000, 0, CFG);
        assertTrue(plan.go(), plan.reason());
        assertTrue(plan.units() >= 17, "the small cheap lot must be included, got " + plan.units());
    }

    @Test
    void anExpensiveSmallLotIsNotWorthBuyingEither() {
        // The same page-1 wall, seen from the buy side: 2,000/item against a 469 resale is not
        // supply worth having, and the sweep must refuse it.
        List<Liquidity.Level> pageOne = List.of(new Liquidity.Level(2_000, 45, 45));
        assertFalse(Sweep.plan(pageOne, 469, 5_000_000L, 2_000, 0, CFG).go());
    }
}
