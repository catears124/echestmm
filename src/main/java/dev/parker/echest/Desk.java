package dev.parker.echest;

import java.util.List;
import java.util.Locale;

/**
 * Desk parameters derived from realised prices.
 *
 * <p>Every number a desk needs - where the bid ladder starts, how big a step is, what to ask into
 * an empty book, the floor below which nothing is sold, how much inventory to carry - is a
 * statistic of trades that actually happened on this account, taken from {@link History}. Nothing
 * here is a constant chosen because it sounded right.
 *
 * <p>Which statistic, and why:
 * <ul>
 *   <li><b>bid start = p10.</b> The tenth percentile of realised prices is the bottom of the range
 *       the book demonstrably trades at. Starting there is what makes acquisition cheap; starting
 *       at the floor is what makes it expensive.</li>
 *   <li><b>ask into an empty book = p90.</b> The book has repeatedly cleared there, so it is a
 *       defensible opening ask when no competitor is visible.</li>
 *   <li><b>minimum price = p25.</b> A floor with evidence behind it: a quarter of realised trades
 *       happened at or below, so selling under it is giving inventory away.</li>
 *   <li><b>spread = interquartile range / median.</b> The bid ceiling has to clear the market's own
 *       dispersion, otherwise a bid gets filled by noise rather than by a genuine discount. A
 *       volatile book therefore demands a wider spread, and this measures volatility instead of
 *       assuming it.</li>
 *   <li><b>step = (p25 - p10) / 8.</b> Eight probes walk the ladder across the cheap end of the
 *       observed range, so the concession rate is scaled to the market rather than fixed.</li>
 *   <li><b>hold cap = cash share / median price.</b> How many units the bankroll can actually carry
 *       at observed prices. Unmeasured until {@code /bal} is known, and reported as such.</li>
 * </ul>
 */
public final class Desk {
    /** Below this many samples the derivation is not trustworthy and the ladder stays off. */
    public static final int MIN_SAMPLES = 20;

    /**
     * Beyond this IQR/median the samples are not one market.
     *
     * <p>Four times the median is already an extraordinarily wide book; the value that triggered
     * this guard was fifty-four times.
     */
    public static final double MAX_COHERENT_DISPERSION = 4.0;
    /**
     * How far under the median the listing minimum sits.
     *
     * <p>A quarter of the median is unreachable in normal trading - the book would have to
     * collapse to a fraction of its own range before this binds - which is the point. It exists
     * to catch a mispricing, not to set policy.
     */
    private static final double MIN_SELL_FRACTION_OF_MEDIAN = 0.25;
    /** Floor on the profit guard: never bid within this fraction of the resting ask. */
    private static final double MIN_SPREAD = 0.10;
    private static final double MAX_SPREAD = 0.45;
    public record Calibration(
            boolean ladderReady,
            Liquidity.Config cfg,
            long bidStart,
            long bidCeiling,
            long bidStep,
            double minSpreadPct,
            int maxHoldUnits,
            int samples,
            String evidence
    ) {}

    private Desk() {}

    /**
     * Derives desk parameters from realised prices.
     *
     * @param samples realised trades for this item and stack size
     * @param cash    liquid balance, or a negative number when unknown
     * @param base    the configuration to refine; its tick is the price grid
     */
    public static Calibration derive(History.Samples samples, long cash, Liquidity.Config base) {
        List<Long> prices = samples == null ? List.of() : samples.all();
        long tick = Math.max(1, base.tick());
        int n = prices.size();

        if (n < MIN_SAMPLES) {
            // No invented numbers. Sell into the live book, learn from the fills, calibrate later.
            return new Calibration(false, base, 0, 0, tick, base.liftMarginPct(), 0, n,
                    "only " + n + " realised trades on record (need " + MIN_SAMPLES + "): "
                            + "bid ladder stays OFF and pricing follows the live book. "
                            + "Run the sell side to gather fills, then /echestmm calibrate.");
        }

        long p10 = Liquidity.quantize(History.quantile(prices, 0.10), tick);
        long p25 = Liquidity.quantize(History.quantile(prices, 0.25), tick);
        long median = Liquidity.quantize(History.quantile(prices, 0.50), tick);
        long p75 = Liquidity.quantize(History.quantile(prices, 0.75), tick);
        long p90 = Liquidity.quantize(History.quantile(prices, 0.90), tick);


        // A price series that spans two orders of magnitude is not one price series. The obsidian
        // desk derived "p10 300 ... p90 38000, IQR/median 5425%" by mixing per-item buys with sale
        // receipts logged before the unit fix, and concluded min sell 300 while the buy side was
        // paying 350 - a desk configured to lose money on every trade. Incoherent history is worse
        // than no history, so it is refused the same way too little history is.
        if (median > 0 && (p75 - p25) > median * MAX_COHERENT_DISPERSION) {
            return new Calibration(false, base, 0, 0, tick, base.liftMarginPct(), 0, n,
                    "history spans " + p10 + " to " + p90 + " per item (IQR/median "
                            + Math.round((p75 - p25) * 100.0 / median) + "%), which is two markets "
                            + "and not one: refusing to derive levels from it. Pricing follows the "
                            + "live book; run /echestmm forget to drop stale receipts.");
        }
        double dispersion = median > 0 ? (p75 - p25) / (double) median : MIN_SPREAD;
        // The spread guard is a profit requirement, not a volatility measure. Feeding raw
        // dispersion into it put the bid ceiling 10 dollars above the start on a bimodal book.
        // What matters is the gap between where we acquire and where the book clears high.
        double headroom = p90 > 0 ? (p90 - median) / (double) p90 : MIN_SPREAD;
        double spread = Math.max(MIN_SPREAD, Math.min(MAX_SPREAD, headroom));

        long bidStart = Math.max(tick, p10);
        // Acquire below the median and ask near the top decile. Deriving the ceiling from raw
        // dispersion instead put it 10 dollars above the start on a bimodal book, which is not a
        // ladder at all; the median is a level the book demonstrably trades through.
        long ceiling = Math.max(bidStart + tick, median);
        long step = Math.max(tick, Liquidity.quantize(Math.max(tick, (ceiling - bidStart) / 12), tick));
        // The listing minimum is a loss guard, not a memory of what we used to get.
        //
        // This was p25 of our own realised sales, which is circular: 500 echest fills put the
        // floor at 6,100, so a book floor of 5,900 could not be undercut, every listing joined the
        // back of the queue, fills slowed, and the slow fills fed the next calibration. A desk
        // must be free to compete at whatever price the market is at today.
        //
        // What the minimum is actually for is refusing to give inventory away - so it sits far
        // below the trading range, where only a genuine mispricing can reach it. The revenue-rate
        // optimiser picks the price; the cost floor defends the basis; this catches absurdity.
        long minPrice = Math.max(tick, Liquidity.quantize(
                (long) Math.floor(median * MIN_SELL_FRACTION_OF_MEDIAN), tick));
        long emptyBookAsk = Math.max(minPrice, p90);

        int holdUnits = 0;
        String holdNote;
        if (cash > 0 && median > 0) {
            holdUnits = (int) Math.max(1, Math.floor(cash * base.maxCashSharePct() / median));
            holdNote = holdUnits + " listings' worth (" + Math.round(base.maxCashSharePct() * 100)
                    + "% of $" + cash + " at the " + median + " median)";
        } else {
            holdNote = "uncapped until /bal is known";
        }

        Liquidity.Config cfg = new Liquidity.Config(minPrice, emptyBookAsk, tick,
                base.maxHoldSeconds(), Math.max(tick, step * 2), base.snipeMarginPct(),
                base.liftMarginPct(), base.maxLiftUnits(), base.maxCashSharePct());

        String evidence = String.format(Locale.ROOT,
                "%d realised trades (%d bought, %d sold), prices per item: p10 %d, p25 %d, median %d, "
                        + "p75 %d, p90 %d. bid %d (p10) -> %d (median), step %d, spread guard %d%% "
                        + "(IQR/median %.0f%%), min sell %d (p25), empty-book ask %d (p90), hold %s.",
                n, samples.bought().size(), samples.sold().size(), p10, p25, median, p75, p90,
                bidStart, ceiling, step, Math.round(spread * 100), dispersion * 100,
                minPrice, emptyBookAsk, holdNote);

        return new Calibration(true, cfg, bidStart, ceiling, step, spread, holdUnits, n, evidence);
    }
}
