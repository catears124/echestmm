package dev.parker.echest;

import java.util.ArrayList;
import java.util.List;

/**
 * The two-sided squeeze: post asks above the book, then take everything under them.
 *
 * <p>Passive market making waits for someone to cross the spread. This does not wait. The sequence
 * is deliberate and the order matters:
 *
 * <ol>
 *   <li><b>Post first.</b> Our inventory goes up at a chosen level {@code L}, above the current
 *       best ask. While cheaper listings still exist nobody buys ours, which is fine - they are
 *       being placed, not sold yet.</li>
 *   <li><b>Then take.</b> Every listing under {@code L} is bought, cheapest first. Each purchase
 *       is cheap by construction (it is under our own ask) and removes a competitor, so the
 *       visible floor climbs toward {@code L}.</li>
 *   <li><b>Now we are the floor.</b> Ordinary buyers arriving at the search see our asks at
 *       {@code L} as the cheapest available, and the bought inventory is relisted at {@code L}
 *       too.</li>
 * </ol>
 *
 * <p>The whole trade is therefore: spend {@code sweepCost} to remove supply, and sell
 * {@code listUnits} at {@code L} instead of at the old floor. It is only worth doing when demand
 * is real - the measured clearing rate has to absorb the inventory inside {@link #HORIZON_SECONDS}
 * - and when the tail under {@code L} is short enough that clearing it costs less than the uplift
 * it creates. Both are checked, and every candidate level is scored rather than assumed.
 *
 * <p>What this cannot do is make demand exist. If the clearing rate says the book absorbs two
 * items a minute, posting forty of them at double the price is not a squeeze, it is inventory.
 * That check is the difference between this and a very expensive way to buy obsidian.
 */
public final class Campaign {
    /** How long we are willing to wait for the posted asks to clear. */
    private static final double HORIZON_SECONDS = 300.0;
    /** Minimum measured clearing rate, in items per second, before a squeeze is allowed at all. */
    private static final double MIN_CLEAR_RATE = 0.02;
    /** A squeeze has to beat this in modelled profit, or it is not worth the capital and risk. */
    private static final long MIN_PROFIT = 5_000;

    public record Plan(
            boolean go,
            long askUnitPrice,      // where our asks go
            long sweepCeiling,      // buy everything at or below this unit price
            int sweepListings,      // how many listings that is
            int sweepUnits,         // how many items that is
            long sweepCost,         // what clearing them costs
            int listUnits,          // how many items we will have resting at askUnitPrice
            long expectedProfit,
            String reason
    ) {
        public static Plan no(String reason) {
            return new Plan(false, 0, 0, 0, 0, 0, 0, 0, reason);
        }
    }

    private Campaign() {}

    /**
     * Scores every level in the book as a squeeze target and returns the best, or a refusal.
     *
     * @param book              competing supply, unit prices, ours excluded
     * @param inventoryUnits    items we hold and could list
     * @param inventoryCostPer  what those items cost us, per item
     * @param freeSlots         auction listing slots available
     * @param unitSize          items per listing on this desk
     * @param cash              liquid balance
     * @param clearRate         measured items per second the book absorbs
     * @param sanityMaxAsk      hard ceiling on where an ask may be posted
     */
    public static Plan plan(List<Liquidity.Level> book, int inventoryUnits, long inventoryCostPer,
                            int freeSlots, int unitSize, long cash, double clearRate,
                            long sanityMaxAsk, Liquidity.Config cfg) {
        if (freeSlots <= 0) return Plan.no("no free listing slots to post asks into");
        if (inventoryUnits <= 0) return Plan.no("no inventory to post");
        if (!Double.isFinite(clearRate) || clearRate < MIN_CLEAR_RATE) {
            return Plan.no("clearing rate " + (Double.isFinite(clearRate)
                    ? String.format("%.3f", clearRate) : "unmeasured")
                    + "/s is below the " + MIN_CLEAR_RATE + "/s needed to absorb posted asks");
        }

        List<Liquidity.Level> sorted = new ArrayList<>();
        for (Liquidity.Level l : book) {
            if (l != null && l.unitPrice() > 0 && l.listings() > 0) sorted.add(l);
        }
        sorted.sort((a, b) -> Long.compare(a.unitPrice(), b.unitPrice()));

        long tick = Math.max(1, cfg.tick());
        long budget = (long) Math.floor(cash * cfg.maxCashSharePct());
        int slotUnits = freeSlots * Math.max(1, unitSize);
        double absorbable = clearRate * HORIZON_SECONDS;

        Plan best = Plan.no(sorted.isEmpty()
                ? "no competing listings: nothing to squeeze, price discovery owns this book"
                : "no level clears the profit, capital and demand tests");
        long bestScore = MIN_PROFIT;

        // Candidate asks: one tick above each competing level, plus one tick above the top.
        for (int i = 0; i < sorted.size(); i++) {
            long ask = Liquidity.quantize(sorted.get(i).unitPrice() + tick, tick);
            if (ask > sanityMaxAsk) break;

            int sweepListings = 0;
            int sweepUnits = 0;
            long sweepCost = 0;
            for (Liquidity.Level l : sorted) {
                if (l.unitPrice() >= ask) break;
                sweepListings += l.listings();
                sweepUnits += l.units();
                sweepCost += (long) l.unitPrice() * l.units();
            }
            if (sweepCost > budget) continue;                     // beyond the capital share
            if (sweepListings > cfg.maxLiftUnits()) continue;     // tail too deep to hold up

            // Post what demand can actually absorb inside the horizon, not everything we hold.
            // Rejecting the whole plan because the inventory is larger than the horizon's demand
            // was wrong: the correct response is a smaller posting, not no trade.
            int totalUnits = inventoryUnits + sweepUnits;
            int listUnits = (int) Math.min(Math.min(totalUnits, slotUnits), Math.floor(absorbable));
            if (listUnits <= 0) continue;

            // Blended cost of everything we would then hold: existing stock at its basis, swept
            // supply at what clearing it cost. Units we cannot post yet stay inventory at cost -
            // neither profit nor loss - so only the posted units are scored.
            long basisTotal = (long) inventoryUnits * Math.max(0, inventoryCostPer) + sweepCost;
            long avgBasis = totalUnits > 0 ? basisTotal / totalUnits : 0;
            long profit = (long) listUnits * (ask - avgBasis);

            if (profit > bestScore) {
                bestScore = profit;
                best = new Plan(true, ask, Liquidity.quantize(ask - tick, tick), sweepListings,
                        sweepUnits, sweepCost, listUnits, profit,
                        "post " + listUnits + " items at " + ask + "/item (cost basis " + avgBasis
                                + "/item), clear " + sweepListings + " listings (" + sweepUnits
                                + " items) under it for " + sweepCost + "; modelled profit " + profit
                                + " against " + String.format("%.0f", absorbable) + " items of demand in "
                                + Math.round(HORIZON_SECONDS) + "s");
            }
        }
        return best;
    }
}
