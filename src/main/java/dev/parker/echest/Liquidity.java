package dev.parker.echest;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure pricing and inventory-liquidation math. No Minecraft types, no I/O, no mutable global
 * state: everything here is a function of an observed book plus measured fill history.
 *
 * <p><b>Everything in this class is a price per item, never a price per listing.</b> That
 * distinction was the bug that broke the obsidian desk: the first version treated a listing as
 * the atom and compared only listings of an identical stack size, so a book advertised as 1x, 8x,
 * 16x and 64x stacks read as completely empty and no bid was ever placed. Obsidian is fungible -
 * 16 for $5,000 beats 64 for $25,000 - so comparison has to happen per item. Ender chests are a
 * special case of the same rule, since they only ever list one at a time.
 *
 * <p>The second premise is that a dump is slot-bound, not price-bound. The auction house allows a
 * fixed number of live listings, so the quantity worth maximising is dollars per listing-slot per
 * second, and liquidity - how fast the book actually clears - decides which price achieves it.
 */
public final class Liquidity {
    /**
     * Competing supply at one unit price, with our own listings already excluded.
     *
     * @param unitPrice price of a single item
     * @param listings  how many separate listings sit at this price (this is queue position)
     * @param units     how many items those listings hold in total (this is what a buyout costs)
     */
    public record Level(long unitPrice, int listings, int units) {}

    /**
     * One realised sale of ours.
     *
     * @param queueAhead competing listings priced strictly below ours when the listing went live
     * @param holdSeconds wall-clock seconds the listing rested before it sold
     */
    public record Fill(int queueAhead, double holdSeconds) {}

    /** A unit-price decision with the evidence behind it. */
    public record Quote(long unitPrice, int queueAhead, double expectedWaitSeconds,
                        double revenuePerHourPerSlot, String reason) {}

    /** A bid ladder decision, in unit price. */
    public record BidPlan(boolean buy, long bid, String reason) {}

    /** A floor-lift (buy-out) decision, in unit price. */
    public record LiftPlan(boolean go, long buyUpTo, int units, long cost, long newFloor,
                           long expectedReturn, String reason) {
        public static LiftPlan no(String reason) {
            return new LiftPlan(false, 0, 0, 0, 0, 0, reason);
        }
    }

    /** Tunables. Money values are whole dollars per item. */
    public record Config(
            long minPrice,          // never list below this unit price
            long fallbackPrice,     // unit price to ask when no competitor is visible
            long tick,              // the auction house price grid, applied to the listing total
            double maxHoldSeconds,  // 0 = no cap; otherwise reject slower candidates when possible
            long ladderStep,        // probe step above the top of the book
            double snipeMarginPct,  // buy anything at or below quote * (1 - this)
            double liftMarginPct,   // required return over cost before lifting the floor
            int maxLiftUnits,       // never buy out more than this many listings in one lift
            double maxCashSharePct  // never spend more than this share of liquid cash on a lift
    ) {
        public static Config defaults() {
            return new Config(1_000, 25_000, 100, 900.0, 500, 0.15, 0.25, 12, 0.20);
        }

        /** One tick under a competing level, snapped down onto the grid. */
        public long undercut(long level) {
            return quantize(level - tick, tick);
        }
    }

    /** Minimum probes a ladder must have room for, or it is not a ladder. */
    private static final int MIN_LADDER_STEPS = 3;
    /** Exponential decay applied to the measured clearing rate per new fill. */
    private static final double CLEAR_RATE_ALPHA = 0.35;

    private Liquidity() {}

    /**
     * Snaps a price down onto the auction house grid.
     *
     * <p>Donut rounds a listing price down to the nearest {@code tick}, so asking 5,599 lists at
     * 5,500: the extra 99 is discarded and the sale receipt never matches the requested price.
     */
    public static long quantize(long price, long tick) {
        if (tick <= 1) return price;
        return price / tick * tick;
    }

    /**
     * Units per second the market clears at or below our price, estimated from our own fills.
     *
     * <p>If a listing sold in {@code t} seconds from queue position {@code d}, then {@code d + 1}
     * units cleared in {@code t} seconds, so the local clearing rate is {@code (d + 1) / t}.
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

    /** Competing listings priced under {@code unitPrice}: the queue we must wait behind. */
    public static int queueAhead(List<Level> levels, List<Long> ownUnitPrices, long unitPrice) {
        int ahead = 0;
        if (levels != null) {
            for (Level l : levels) {
                if (l != null && l.unitPrice < unitPrice) ahead += Math.max(0, l.listings);
            }
        }
        // Our own cheaper listings also have to clear before this one can sell.
        if (ownUnitPrices != null) {
            for (Long own : ownUnitPrices) {
                if (own != null && own < unitPrice) ahead++;
            }
        }
        return ahead;
    }

    /**
     * How much company we have on the sell side, which is what decides the pricing tactic.
     *
     * <p>These are not three shades of the same behaviour, they are three different trades:
     * <ul>
     *   <li>{@link #OPEN} - nobody else is selling. There is no competitive price, only a
     *       willingness-to-pay we have not measured yet, so the ask ratchets upward until fills
     *       slow down. Undercutting a floor that does not exist is how the desk gave away $30k+
     *       per ender chest.</li>
     *   <li>{@link #THIN} - a handful of listings stand between us and the top. Buying them is
     *       usually cheaper than pricing under them: the cost is bounded, and afterwards we
     *       <em>are</em> the floor and can list where they used to be.</li>
     *   <li>{@link #CROWDED} - too many competitors to buy out. Undercut and turn inventory,
     *       because revenue rate beats price per unit when the queue is long.</li>
     * </ul>
     */
    public enum Regime { OPEN, THIN, CROWDED }

    /** Competing listings at or above which a book is too deep to buy out. */
    public static final int THIN_BOOK_LISTINGS = 6;

    public static Regime classify(List<Level> levels) {
        int listings = 0;
        for (Level l : sortedCompeting(levels)) listings += Math.max(0, l.listings);
        if (listings == 0) return Regime.OPEN;
        return listings < THIN_BOOK_LISTINGS ? Regime.THIN : Regime.CROWDED;
    }

    /**
     * The unit price to list at right now.
     *
     * <p>Candidates are one tick under every competing level, plus one probe above the top of the
     * book. Each is scored by revenue rate - price divided by expected wait, where the wait is
     * {@code (queue ahead + 1) / clearRate}. Best revenue rate wins; ties go to the cheaper,
     * faster-filling price. Until a fill has been measured the clearing rate is unknown, so the
     * quote undercuts the floor to buy that measurement as quickly as possible.
     */
    public static Quote quote(List<Level> levels, List<Long> ownUnitPrices, double clearRate, Config cfg) {
        List<Level> book = sortedCompeting(levels);
        if (book.isEmpty()) {
            long open = Math.max(cfg.minPrice, cfg.fallbackPrice);
            return new Quote(open, queueAhead(book, ownUnitPrices, open), Double.NaN, Double.NaN,
                    "no competitor visible; open regime");
        }

        long floor = book.getFirst().unitPrice;
        if (!Double.isFinite(clearRate) || clearRate <= 0.0) {
            long price = Math.max(cfg.minPrice, cfg.undercut(floor));
            return new Quote(price, queueAhead(book, ownUnitPrices, price), Double.NaN, Double.NaN,
                    "clearing rate unmeasured; undercutting floor " + floor + " to measure it");
        }

        List<Long> candidates = new ArrayList<>(book.size() + 1);
        for (Level l : book) {
            long p = cfg.undercut(l.unitPrice);
            if (p >= cfg.minPrice) candidates.add(p);
        }
        long above = quantize(book.getLast().unitPrice + cfg.ladderStep, cfg.tick);
        if (above >= cfg.minPrice) candidates.add(above);
        if (candidates.isEmpty()) {
            long price = Math.max(cfg.minPrice, quantize(cfg.minPrice, cfg.tick));
            return new Quote(price, queueAhead(book, ownUnitPrices, price), Double.NaN, Double.NaN,
                    "every undercut price is below the minimum; listing at the minimum");
        }

        long bestPrice = 0;
        int bestQueue = 0;
        double bestWait = Double.NaN;
        double bestScore = Double.NEGATIVE_INFINITY;
        boolean bestWithinHold = false;

        for (long price : candidates) {
            int ahead = queueAhead(book, ownUnitPrices, price);
            double wait = (ahead + 1) / clearRate;
            double score = price / wait;                       // dollars per second per slot
            boolean withinHold = cfg.maxHoldSeconds <= 0.0 || wait <= cfg.maxHoldSeconds;
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

        // Never undercut our own resting asks. Pricing under ourselves does not win the sale from
        // a competitor - it wins it from us, at a discount we chose to hand over. When the book
        // falls below where we already rest, the right move is to join our own level, not dive
        // under it. (Cutting our own price is a decision for the ladder, not for a quote.)
        long ownFloor = Long.MAX_VALUE;
        if (ownUnitPrices != null) {
            for (Long own : ownUnitPrices) {
                if (own != null && own > 0) ownFloor = Math.min(ownFloor, own);
            }
        }
        String selfNote = "";
        if (ownFloor != Long.MAX_VALUE && bestPrice < ownFloor) {
            bestPrice = ownFloor;
            bestQueue = queueAhead(book, ownUnitPrices, bestPrice);
            bestWait = (bestQueue + 1) / clearRate;
            selfNote = "; held at our own " + ownFloor + " rather than undercutting ourselves";
        }

        String reason = "queue " + bestQueue + " ahead, ~" + Math.round(bestWait) + "s to fill at "
                + String.format("%.3f", clearRate) + " units/s"
                + (bestWithinHold ? "" : "; nothing fits the hold budget, taking the best available")
                + selfNote;
        return new Quote(bestPrice, bestQueue, bestWait, bestScore * 3_600.0, reason);
    }

    /** Anything listed at or below this unit price is cheap enough to buy for resale. */
    public static long snipeCeiling(long quoteUnitPrice, Config cfg) {
        if (quoteUnitPrice <= 0) return 0;
        return quantize((long) Math.floor(quoteUnitPrice * (1.0 - cfg.snipeMarginPct)), cfg.tick);
    }
    /** How quickly a fill has to arrive before the ask is treated as too low. */
    private static final double FAST_FILL_SECONDS = 6.0;
    /** How long the book has to ignore us before the ask comes back down. */
    private static final double STALE_ASK_SECONDS = 90.0;
    private static final double RAISE_FACTOR = 1.12;
    private static final double LOWER_FACTOR = 0.94;

    /**
     * Price discovery for the case where nobody else is selling, or where we are the whole top of
     * the book. Undercutting cannot answer "what is this worth?" - only probing can.
     *
     * <p>The signal is hold time, which is already measured per fill. A listing that clears in two
     * seconds was underpriced, so the ask ratchets up 12%; if it clears in two seconds again it
     * ratchets again, compounding until the market stops taking it. Silence for
     * {@link #STALE_ASK_SECONDS} walks it back down 6%. Ender chests filling instantly at 6,800
     * are exactly this situation: past sales say 6,800 because that is all we ever asked.
     *
     * @param current           ask in force, or 0 to start from {@code opening}
     * @param medianHoldSeconds median hold time of recent fills, or NaN when unmeasured
     * @param secondsSinceFill  time since the last fill, or a negative number when none yet
     * @param opening           where to start when there is no ask yet, from realised prices
     * @param sanityMax         hard ceiling, so a run of fast fills cannot post an absurd ask
     */
    public static long discoveryAsk(long current, double medianHoldSeconds, double secondsSinceFill,
                                    long opening, long sanityMax, Config cfg) {
        long tick = Math.max(1, cfg.tick);
        long floor = Math.max(tick, cfg.minPrice);
        long cap = Math.max(floor, sanityMax);
        long ask = current > 0 ? current : Math.max(floor, opening);

        if (Double.isFinite(medianHoldSeconds) && medianHoldSeconds >= 0.0
                && medianHoldSeconds <= FAST_FILL_SECONDS) {
            long raised = quantize(Math.round(ask * RAISE_FACTOR), tick);
            ask = Math.max(raised, ask + tick);          // always move at least one tick
        } else if (secondsSinceFill >= STALE_ASK_SECONDS) {
            long lowered = quantize(Math.round(ask * LOWER_FACTOR), tick);
            ask = Math.min(lowered, ask - tick);
        }
        return Math.max(floor, Math.min(cap, quantize(ask, tick)));
    }

    /**
     * Two-sided market making on a thin but liquid book: rest an ask high, then walk a bid up from
     * far below it until the book starts hitting you.
     *
     * <p>A naive buyer pays the floor, which drifts up as they lift it - an average cost above
     * their own resting ask. A ladder instead starts well under the floor and concedes one step at
     * a time only while nothing fills, so acquisition happens at the bottom of the range. A fill
     * walks the bid back down, because a book that just traded at your bid will trade there again.
     *
     * <p>The ceiling is the lower of a target derived from realised prices and what the resting
     * ask can support at {@code minSpreadPct}. It is then widened, if necessary, to leave room for
     * {@link #MIN_LADDER_STEPS} probes: a ceiling that lands on the start is not a ladder, which
     * is exactly what a 45% spread against a 36,200 ask produced - a 10 dollar range.
     *
     * @param currentBid the bid in force, or 0 to start the ladder
     * @param target     ceiling implied by realised prices, or 0 for none
     * @param askInForce our own resting ask; never bid within {@code minSpreadPct} of it
     */
    public static BidPlan ladderBid(long currentBid, long target, long askInForce, boolean quiet,
                                    boolean filled, long start, long step, double minSpreadPct,
                                    Config cfg) {
        long tick = Math.max(1, cfg.tick);
        long floorBid = quantize(Math.max(tick, start), tick);
        long stepSize = Math.max(tick, quantize(Math.max(tick, step), tick));

        long ceiling = target > 0 ? quantize(target, tick) : Long.MAX_VALUE;
        if (askInForce > 0) {
            long spreadCeiling = quantize((long) Math.floor(askInForce * (1.0 - minSpreadPct)), tick);
            ceiling = Math.min(ceiling, spreadCeiling);
        }
        if (ceiling == Long.MAX_VALUE) {
            return new BidPlan(false, 0, "no ask and no target to price a bid against");
        }

        // The spread ceiling is a profit constraint and is never widened - an earlier version
        // "made room" by overriding it, which quietly bid inside the margin it was there to
        // protect. When the room is genuinely tight the step shrinks instead, so a ladder still
        // walks rather than sitting on one price.
        if (ceiling < floorBid) {
            return new BidPlan(false, 0, "ceiling " + ceiling + " is under the " + floorBid
                    + " ladder start: the resting ask cannot support this bid at "
                    + Math.round(minSpreadPct * 100) + "% spread");
        }
        long room = ceiling - floorBid;
        if (room > 0 && stepSize * MIN_LADDER_STEPS > room) {
            stepSize = Math.max(tick, quantize(room / MIN_LADDER_STEPS, tick));
        }

        long bid = currentBid <= 0 ? floorBid : quantize(currentBid, tick);
        if (bid < floorBid) bid = floorBid;
        String reason;
        if (filled) {
            bid = Math.max(floorBid, bid - stepSize);
            reason = "filled at the last bid; stepping back down to " + bid;
        } else if (quiet) {
            if (bid >= ceiling) {
                return new BidPlan(true, ceiling,
                        "bid is at the " + ceiling + " ceiling; waiting rather than paying up");
            }
            bid = Math.min(ceiling, bid + stepSize);
            reason = "nothing filled this probe; conceding a step to " + bid;
        } else {
            reason = "holding the bid at " + bid;
        }
        return new BidPlan(true, Math.min(bid, ceiling), reason);
    }

    /**
     * Plans a floor lift: buy out every competing item under {@code targetFloor} so the visible
     * floor moves up to the next level, then sell our own inventory into the thinner, higher book.
     *
     * <p>Prices and the target are unit prices; {@code cost} is real money, so it multiplies unit
     * price by the number of items each level holds.
     */
    public static LiftPlan planFloorLift(List<Level> levels, long targetFloor, int unitsHeld,
                                         int freeSlots, long cash, Config cfg) {
        List<Level> book = sortedCompeting(levels);
        if (book.isEmpty()) return LiftPlan.no("no competing listings to clear");
        if (targetFloor <= 0) return LiftPlan.no("no target floor");
        if (freeSlots <= 0) return LiftPlan.no("no free listing slot to sell into");

        int listings = 0;
        int units = 0;
        long cost = 0;
        for (Level l : book) {
            if (l.unitPrice >= targetFloor) break;
            listings += l.listings;
            if (listings > cfg.maxLiftUnits) {
                return LiftPlan.no("cheap tail deeper than " + cfg.maxLiftUnits
                        + " listings; too expensive to lift");
            }
            units += l.units;
            cost += (long) l.unitPrice * l.units;
        }
        if (listings == 0) return LiftPlan.no("floor already at or above the target");

        long budget = (long) Math.floor(cash * cfg.maxCashSharePct);
        if (cost > budget) {
            return LiftPlan.no("lift costs " + cost + " but the cash budget is " + budget);
        }

        long newFloor = targetFloor;
        for (Level l : book) {
            if (l.unitPrice >= targetFloor) { newFloor = l.unitPrice; break; }
        }
        long liftedAsk = Math.max(cfg.minPrice, cfg.undercut(newFloor));
        long oldAsk = Math.max(cfg.minPrice, cfg.undercut(book.getFirst().unitPrice));
        if (liftedAsk <= oldAsk) return LiftPlan.no("lift would not raise our own ask");

        // Resale of what we buy, plus the uplift on inventory we were going to list anyway.
        int sellableHeld = Math.min(unitsHeld, Math.max(0, freeSlots - 1));
        long resale = (long) units * liftedAsk;
        long uplift = (liftedAsk - oldAsk) * sellableHeld;
        long expectedReturn = resale + uplift;
        long required = (long) Math.ceil(cost * (1.0 + cfg.liftMarginPct));
        if (expectedReturn < required) {
            return LiftPlan.no("modelled return " + expectedReturn + " under the required " + required);
        }

        return new LiftPlan(true, quantize(targetFloor - cfg.tick, cfg.tick), units, cost, newFloor,
                expectedReturn, "clear " + listings + " listings (" + units + " items) under "
                        + targetFloor + " for " + cost + "; ask moves " + oldAsk + " -> " + liftedAsk);
    }

    private static List<Level> sortedCompeting(List<Level> levels) {
        List<Level> out = new ArrayList<>();
        if (levels == null) return out;
        for (Level l : levels) {
            if (l != null && l.unitPrice > 0 && l.listings > 0) out.add(l);
        }
        out.sort((a, b) -> Long.compare(a.unitPrice, b.unitPrice));
        return out;
    }
}
