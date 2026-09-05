package dev.parker.echest;

import java.util.ArrayList;
import java.util.List;

/**
 * Plans acquisition through a buy order rather than through clicks.
 *
 * <p>A DonutSMP buy order fills <em>best price first</em>, from the cheapest ask upward, until it
 * runs out of items to buy or hits the price ceiling we named. That makes one order the exact
 * instrument the click-sniper was imitating: it clears the cheap tail of the book in a single
 * action instead of racing other players listing by listing.
 *
 * <p>The economics that follow from best-first fill:
 * <ul>
 *   <li><b>The ceiling is the only price decision.</b> Everything below it gets bought, so the
 *       ceiling is set where the marginal item stops being worth reselling - not at the average.</li>
 *   <li><b>Cost is not ceiling times quantity.</b> Cheap listings fill first, so the realised
 *       average sits well under the ceiling. Budgeting at {@code ceiling * units} would refuse
 *       affordable sweeps, so the plan costs the book level by level.</li>
 *   <li><b>Partial stacks help rather than hurt.</b> They are just cheap per-item supply, and
 *       per-item pricing already handles them - a 17-item listing at 300/item fills before a
 *       64-item listing at 320/item.</li>
 * </ul>
 *
 * <p>Sweeping the tail also lifts the visible floor, which is the price-manipulation leg: after
 * the cheap supply is ours, the cheapest remaining ask is higher, and our own sell orders rest
 * above that.
 */
public final class Sweep {
    /**
     * A planned buy order.
     *
     * @param go             whether to post it
     * @param ceilingPrice   the per-item price named in the order
     * @param units          how many items to order
     * @param modelledCost   what the book says those units actually cost, filling cheapest first
     * @param modelledResale what they are worth at our own ask
     * @param reason         why, in the words the log uses
     */
    public record Plan(boolean go, long ceilingPrice, int units, long modelledCost,
                       long modelledResale, String reason) {
        static Plan no(String why) {
            return new Plan(false, 0, 0, 0, 0, why);
        }

        public long modelledProfit() {
            return modelledResale - modelledCost;
        }

        /** The realised average per item, which is what the ceiling gets compared against. */
        public long averageCost() {
            return units <= 0 ? 0 : Math.round(modelledCost / (double) units);
        }
    }

    private Sweep() {}

    /**
     * Plans one buy order against the visible book.
     *
     * @param levels     the competing book, any order, per-item prices
     * @param resaleAsk  the per-item price we expect to sell at
     * @param cash       spendable balance
     * @param freeUnits  how many more items we are willing to hold
     * @param minProfit  the modelled profit an order must clear to be worth posting
     * @param cfg        desk tunables; {@code snipeMarginPct} sets the required margin and
     *                   {@code maxCashSharePct} the share of cash one order may commit
     */
    public static Plan plan(List<Liquidity.Level> levels, long resaleAsk, long cash,
                            int freeUnits, long minProfit, Liquidity.Config cfg) {
        if (resaleAsk <= 0) return Plan.no("no resale ask; nothing to price a sweep against");
        if (cash <= 0) return Plan.no("cash unknown; run /bal");
        if (freeUnits <= 0) return Plan.no("no room to hold more");

        // The highest per-item price still worth paying, given what we can sell at.
        long ceiling = Liquidity.snipeCeiling(resaleAsk, cfg);
        // Note this is deliberately not floored by cfg.minPrice(): that is the lowest price we
        // will ever *list* at, and it has nothing to say about how cheaply we are willing to buy.
        if (ceiling <= 0) return Plan.no("resale ask " + resaleAsk + " leaves no margin");

        long budget = (long) Math.floor(cash * cfg.maxCashSharePct());
        if (budget <= 0) return Plan.no("cash share leaves no budget");

        List<Liquidity.Level> book = new ArrayList<>();
        if (levels != null) {
            for (Liquidity.Level l : levels) {
                if (l != null && l.unitPrice() > 0 && l.units() > 0) book.add(l);
            }
        }
        if (book.isEmpty()) return Plan.no("no visible supply to sweep");
        book.sort((a, b) -> Long.compare(a.unitPrice(), b.unitPrice()));

        // Walk the book cheapest first, exactly as the order will fill.
        int units = 0;
        long cost = 0;
        long lastTaken = 0;
        for (Liquidity.Level level : book) {
            if (level.unitPrice() > ceiling) break;
            int take = Math.min(level.units(), freeUnits - units);
            if (take <= 0) break;
            long levelCost = level.unitPrice() * (long) take;
            if (cost + levelCost > budget) {
                // Partially fill this level with whatever budget is left.
                take = (int) ((budget - cost) / level.unitPrice());
                if (take <= 0) break;
                levelCost = level.unitPrice() * (long) take;
            }
            units += take;
            cost += levelCost;
            lastTaken = level.unitPrice();
            if (units >= freeUnits) break;
        }

        if (units <= 0) {
            return Plan.no("cheapest ask " + book.getFirst().unitPrice() + " is above the "
                    + ceiling + " ceiling implied by a " + resaleAsk + " resale");
        }

        long resale = resaleAsk * (long) units;
        long profit = resale - cost;
        if (profit < minProfit) {
            return Plan.no("modelled profit " + profit + " on " + units + " items is not worth an order");
        }

        // Name the ceiling at the last level we actually want, not at the theoretical maximum:
        // an order priced higher would keep filling into asks we decided were too expensive.
        long named = Math.max(1L, Math.min(ceiling, lastTaken));
        return new Plan(true, named, units, cost, resale,
                "sweep " + units + " items to " + named + "/item (avg "
                        + Math.round(cost / (double) units) + ", resale " + resaleAsk
                        + ", modelled profit " + profit + ")");
    }
}
