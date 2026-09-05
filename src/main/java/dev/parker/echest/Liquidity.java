package dev.parker.echest;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure pricing and inventory-liquidation math. No Minecraft types, no I/O, no mutable global
 * state: everything here is a function of an observed book plus measured fill history, which is
 * what makes it testable.
 *
 * <p>The premise is that a dump is slot-bound, not price-bound. The auction house allows a fixed
 * number of live listings, so the quantity worth maximising is <em>dollars per listing-slot per
 * second</em>, not headline price. A listing priced under the whole book fills almost at once but
 * gives away spread; a listing above ten competitors earns more per sale and may never fill inside
 * the session. Liquidity - how fast the book actually clears - decides which is better, so the
 * clearing rate is measured from our own fills and the price is derived from it.
 */
public final class Liquidity {
    /** Competing listings at one exact price, with our own listings already excluded. */
    public record Level(long price, int count) {}

    /**
     * One realised sale of ours.
     *
     * @param queueAhead competing listings priced strictly below ours when the listing went live
     * @param holdSeconds wall-clock seconds the listing rested before it sold
     */
    public record Fill(int queueAhead, double holdSeconds) {}

    /** A price decision with the evidence behind it. */
    public record Quote(long price, int queueAhead, double expectedWaitSeconds,
                        double revenuePerHourPerSlot, String reason) {}

    /** A floor-lift (buy-out) decision. */
    public record LiftPlan(boolean go, long buyUpTo, int units, long cost, long newFloor,
                           long expectedReturn, String reason) {
        public static LiftPlan no(String reason) {
            return new LiftPlan(false, 0, 0, 0, 0, 0, reason);
        }
    }

    /** Tunables. All money values are whole dollars, matching the auction house. */
    public record Config(
            long minPrice,          // never list below this
            long fallbackPrice,     // opening price when no competitor is visible
            long undercut,          // how far under a competing level to list
            double maxHoldSeconds,  // 0 = no cap; otherwise reject slower candidates when possible
            long ladderStep,        // probe step above the top of the book
            double snipeMarginPct,  // buy anything at or below quote * (1 - this)
            double liftMarginPct,   // required return over cost before lifting the floor
            int maxLiftUnits,       // never buy out more than this many listings in one lift
            double maxCashSharePct  // never spend more than this share of liquid cash on a lift
    ) {
        public static Config defaults() {
            return new Config(1_000, 25_000, 1, 900.0, 500, 0.15, 0.25, 12, 0.20);
        }
    }

    /** Exponential decay applied to the measured clearing rate per new fill. */
    private static final double CLEAR_RATE_ALPHA = 0.35;

    private Liquidity() {}

    /**
     * Units per second the market clears at or below our price, estimated from our own fills.
     *
     * <p>If a listing sold in {@code t} seconds from queue position {@code d}, then {@code d + 1}
     * units cleared in {@code t} seconds, so the local clearing rate is {@code (d + 1) / t}. That
     * is the whole estimator, and it is why queue depth is recorded at listing time.
     *
     * @return units per second, or NaN when no usable fill has been observed yet
     */
    public static double clearRate(List<Fill> fills) {
        if (fills == null || fills.isEmpty()) return Double.NaN;
        double rate = Double.NaN;
        for (Fill f : fills) {
            if (f == null || !(f.holdSeconds > 0.0) || f.queueAhead < 0) continue;
            double sample = (f.queueAhead + 1) / f.holdSeconds;
            if (!Double.isFinite(sample) || sample <= 0.0) continue;
            rate = Double.isNaN(rate) ? sample : CLEAR_RATE_ALPHA * sample + (1.0 - CLEAR_RATE_ALPHA) * rate;
        }
        return rate;
    }

    /** Competing listings strictly cheaper than {@code price}: the queue we must wait behind. */
    public static int queueAhead(List<Level> levels, List<Long> ownPrices, long price) {
        int ahead = 0;
        if (levels != null) {
            for (Level l : levels) {
                if (l != null && l.price < price) ahead += Math.max(0, l.count);
            }
        }
        // Our own cheaper listings also have to clear before this one can sell.
        if (ownPrices != null) {
            for (Long own : ownPrices) {
                if (own != null && own < price) ahead++;
            }
        }
        return ahead;
    }

    /**
     * The price to list one unit at right now.
     *
     * <p>Candidates are one tick under every competing level, plus one probe above the top of the
     * book. Each is scored by revenue rate - price divided by its expected wait, where the wait is
     * {@code (queue ahead + 1) / clearRate}. The best revenue rate wins; ties go to the cheaper,
     * faster-filling price. Until a fill has been measured the clearing rate is unknown, so the
     * quote undercuts the floor to buy that measurement as quickly as possible.
     */
    public static Quote quote(List<Level> levels, List<Long> ownPrices, double clearRate, Config cfg) {
        List<Level> book = sortedCompeting(levels);
        if (book.isEmpty()) {
            long open = Math.max(cfg.minPrice, cfg.fallbackPrice);
            return new Quote(open, queueAhead(book, ownPrices, open), Double.NaN, Double.NaN,
                    "no competitor visible; open regime");
        }

        long floor = book.getFirst().price;
        if (!Double.isFinite(clearRate) || clearRate <= 0.0) {
            long price = Math.max(cfg.minPrice, floor - cfg.undercut);
            return new Quote(price, queueAhead(book, ownPrices, price), Double.NaN, Double.NaN,
                    "clearing rate unmeasured; undercutting floor " + floor + " to measure it");
        }

        List<Long> candidates = new ArrayList<>(book.size() + 1);
        for (Level l : book) {
            long p = l.price - cfg.undercut;
            if (p >= cfg.minPrice) candidates.add(p);
        }
        long above = book.getLast().price + cfg.ladderStep;
        if (above >= cfg.minPrice) candidates.add(above);
        if (candidates.isEmpty()) {
            long price = cfg.minPrice;
            return new Quote(price, queueAhead(book, ownPrices, price), Double.NaN, Double.NaN,
                    "every undercut price is below the minimum; listing at the minimum");
        }

        long bestPrice = 0;
        int bestQueue = 0;
        double bestWait = Double.NaN;
        double bestScore = Double.NEGATIVE_INFINITY;
        boolean bestWithinHold = false;

        for (long price : candidates) {
            int ahead = queueAhead(book, ownPrices, price);
            double wait = (ahead + 1) / clearRate;
            double score = price / wait;                       // dollars per second per slot
            boolean withinHold = cfg.maxHoldSeconds <= 0.0 || wait <= cfg.maxHoldSeconds;
            // A candidate inside the hold budget always beats one outside it, however rich.
            boolean better = bestScore == Double.NEGATIVE_INFINITY
                    || (withinHold && !bestWithinHold)
                    || (withinHold == bestWithinHold && score > bestScore);
            if (!better) continue;
            bestPrice = price;
            bestQueue = ahead;
            bestWait = wait;
            bestScore = score;
            bestWithinHold = withinHold;
        }

        String reason = "queue " + bestQueue + " ahead, ~" + Math.round(bestWait) + "s to fill at "
                + String.format("%.3f", clearRate) + " units/s"
                + (bestWithinHold ? "" : "; nothing fits the hold budget, taking the best available");
        return new Quote(bestPrice, bestQueue, bestWait, bestScore * 3_600.0, reason);
    }

    /** Anything listed at or below this is cheap enough to buy for resale. */
    public static long snipeCeiling(long quotePrice, Config cfg) {
        if (quotePrice <= 0) return 0;
        return (long) Math.floor(quotePrice * (1.0 - cfg.snipeMarginPct));
    }

    /**
     * Plans a floor lift: buy out every competing listing under {@code targetFloor} so the visible
     * floor moves up to the next level, then sell our own inventory into the thinner, higher book.
     *
     * <p>The trade pays for itself two ways: the bought units are themselves resold at the lifted
     * price, and every unit we already hold now lists higher. It is refused unless the modelled
     * return clears cost by {@code liftMarginPct}, the cash outlay stays inside
     * {@code maxCashSharePct} of liquid cash, and the tail is short enough that a rival dumper
     * cannot simply refill it.
     */
    public static LiftPlan planFloorLift(List<Level> levels, long targetFloor, int unitsHeld,
                                         int freeSlots, long cash, Config cfg) {
        List<Level> book = sortedCompeting(levels);
        if (book.isEmpty()) return LiftPlan.no("no competing listings to clear");
        if (targetFloor <= 0) return LiftPlan.no("no target floor");
        if (freeSlots <= 0) return LiftPlan.no("no free listing slot to sell into");

        int units = 0;
        long cost = 0;
        for (Level l : book) {
            if (l.price >= targetFloor) break;
            for (int i = 0; i < Math.max(0, l.count); i++) {
                if (units >= cfg.maxLiftUnits) return LiftPlan.no("cheap tail deeper than "
                        + cfg.maxLiftUnits + " listings; too expensive to lift");
                units++;
                cost += l.price;
            }
        }
        if (units == 0) return LiftPlan.no("floor already at or above the target");

        long budget = (long) Math.floor(cash * cfg.maxCashSharePct);
        if (cost > budget) {
            return LiftPlan.no("lift costs " + cost + " but the cash budget is " + budget);
        }

        long newFloor = targetFloor;
        for (Level l : book) {
            if (l.price >= targetFloor) { newFloor = l.price; break; }
        }
        long liftedAsk = Math.max(cfg.minPrice, newFloor - cfg.undercut);
        long oldAsk = Math.max(cfg.minPrice, book.getFirst().price - cfg.undercut);
        if (liftedAsk <= oldAsk) return LiftPlan.no("lift would not raise our own ask");

        // Resale of what we buy, plus the uplift on the inventory we were going to list anyway.
        int sellableHeld = Math.min(unitsHeld, Math.max(0, freeSlots - 1));
        long resale = (long) units * liftedAsk;
        long uplift = (liftedAsk - oldAsk) * sellableHeld;
        long expectedReturn = resale + uplift;
        long required = (long) Math.ceil(cost * (1.0 + cfg.liftMarginPct));
        if (expectedReturn < required) {
            return LiftPlan.no("modelled return " + expectedReturn + " under the required " + required);
        }

        return new LiftPlan(true, targetFloor - 1, units, cost, newFloor, expectedReturn,
                "clear " + units + " listings under " + targetFloor + " for " + cost
                        + "; ask moves " + oldAsk + " -> " + liftedAsk);
    }

    private static List<Level> sortedCompeting(List<Level> levels) {
        List<Level> out = new ArrayList<>();
        if (levels == null) return out;
        for (Level l : levels) {
            if (l != null && l.price > 0 && l.count > 0) out.add(l);
        }
        out.sort((a, b) -> Long.compare(a.price, b.price));
        return out;
    }
}
