package dev.parker.echest;

import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Rate ceiling for everything the server can see: slash commands, GUI slot clicks and dialog
 * button presses alike.
 *
 * <p>Donut kicks for spam <em>without sending a warning first</em>. A backoff that waits for a
 * "you are sending commands too quickly" line therefore never fires - the observed run took five
 * kicks in forty minutes and logged exactly zero rate-limit messages. So this governor does not
 * wait to be told:
 *
 * <ul>
 *   <li>It meters every action through one token bucket, because the server counts packets, not
 *       intentions. Commands and clicks share the budget.</li>
 *   <li>Its ceiling <b>persists to disk</b>. A kick disconnects the client, and in-memory state
 *       resets to full speed on reconnect - which is exactly how a single kick becomes five.</li>
 *   <li>A disconnect drops the ceiling hard and immediately; recovery is slow and only accrues
 *       while actually trading without incident.</li>
 * </ul>
 *
 * <p>Measured on this account: a sustained 2.0-2.2 actions/second earned a kick within minutes,
 * every time. The default ceiling is set well under that.
 */
public final class ActionPacer {
    /**
     * Actions per second the pacer starts at on a fresh install.
     *
     * <p>The kicked session averaged 0.85 actions/s overall but burst to 2.0-2.2/s for tens of
     * seconds at a time, and every kick followed such a burst. What gets you kicked is the burst,
     * not the average, and the bucket below is what removes bursts - so the sustained ceiling can
     * sit higher than the first cautious guess without going anywhere near the observed threshold.
     *
     * <p>1.20/s sustained is 45% under the slowest rate ever seen to earn a kick, and the ratchet
     * still drops to 0.25/s the instant one arrives. The honest caveat: the kick threshold was
     * measured on bursty traffic, so a sustained rate this high has not been proven safe - it is
     * an inference from where the bursts were. If penalties start appearing in {@code /echestmm
     * pace}, this number is why, and it is one constant to lower.
     */
    private static final double DEFAULT_CAP = 1.20;
    private static final double MIN_CAP = 0.40;
    private static final double MAX_CAP = 1.60;
    /** Hard drop applied the moment a kick or a rate-limit line is seen. */
    private static final double PENALTY = 0.25;
    /** Earned back per clean interval, so recovery takes tens of minutes, not seconds. */
    private static final double RECOVERY = 0.05;
    private static final long RECOVERY_INTERVAL_MS = 10 * 60 * 1000L;
    /** At most this many actions may be spent back to back before the rate binds. */
    private static final double BUCKET_BURST = 2.0;

    private static double cap = DEFAULT_CAP;
    private static double tokens = BUCKET_BURST;
    private static long lastRefillMs;
    private static long cleanSinceMs;
    private static int penalties;
    private static long spent;
    private static boolean loaded;

    private ActionPacer() {}

    /** Actions per second currently allowed. */
    public static synchronized double cap() {
        ensureLoaded();
        return cap;
    }

    public static synchronized int penalties() {
        ensureLoaded();
        return penalties;
    }

    public static synchronized long spent() {
        return spent;
    }

    /** Seconds of clean operation accrued toward the next recovery step. */
    public static synchronized long cleanSeconds(long nowMs) {
        ensureLoaded();
        return cleanSinceMs <= 0 ? 0 : Math.max(0, (nowMs - cleanSinceMs) / 1000);
    }

    /**
     * Takes {@code weight} actions out of the bucket, or refuses.
     *
     * @param weight 1.0 for a command, a click or a confirmation; a fraction for cheap
     *               inventory shuffling, where several clicks amount to one server interaction
     * @return true when the caller may act now
     */
    public static synchronized boolean charge(long nowMs, double weight) {
        ensureLoaded();
        refill(nowMs);
        double need = Math.max(0.0, weight);
        if (tokens + 1e-9 < need) return false;
        tokens -= need;
        spent++;
        maybeRecover(nowMs);
        return true;
    }

    /** Returns tokens taken for an action that turned out not to be sent. */
    public static synchronized void refund(double weight) {
        tokens = Math.min(BUCKET_BURST, tokens + Math.max(0.0, weight));
        if (spent > 0) spent--;
    }

    /**
     * A kick, a disconnect while trading, or an explicit rate-limit line. Drops the ceiling now
     * and persists it, so the next session starts slower instead of repeating the mistake.
     */
    public static synchronized void penalize(long nowMs, String reason) {
        ensureLoaded();
        double before = cap;
        cap = Math.max(MIN_CAP, cap - PENALTY);
        tokens = 0.0;
        penalties++;
        cleanSinceMs = nowMs;
        save();
        MarketFeed.log("pacer", "penalty (" + reason + "): cap " + fmt(before) + " -> " + fmt(cap)
                + "/s, penalties=" + penalties);
    }

    /**
     * Clears the recorded penalties and returns to the default ceiling. For the case where the
     * ratchet fired on something that was not a kick - the first version penalised a deliberate
     * quit, which cost 0.35/s of throughput for nothing.
     */
    public static synchronized void reset(long nowMs, String reason) {
        ensureLoaded();
        double before = cap;
        cap = DEFAULT_CAP;
        penalties = 0;
        tokens = 0.0;
        cleanSinceMs = nowMs;
        lastRefillMs = nowMs;
        save();
        MarketFeed.log("pacer", "reset (" + reason + "): cap " + fmt(before) + " -> " + fmt(cap) + "/s");
    }

    /** Restarts the clean-uptime clock, e.g. when trading is switched on. */
    public static synchronized void noteResumed(long nowMs) {
        ensureLoaded();
        cleanSinceMs = nowMs;
        tokens = Math.min(tokens, BUCKET_BURST);
        lastRefillMs = nowMs;
    }

    private static void refill(long nowMs) {
        if (lastRefillMs <= 0) { lastRefillMs = nowMs; return; }
        double elapsed = (nowMs - lastRefillMs) / 1000.0;
        if (elapsed <= 0) return;
        lastRefillMs = nowMs;
        tokens = Math.min(BUCKET_BURST, tokens + elapsed * cap);
    }

    private static void maybeRecover(long nowMs) {
        if (cleanSinceMs <= 0) { cleanSinceMs = nowMs; return; }
        if (nowMs - cleanSinceMs < RECOVERY_INTERVAL_MS) return;
        cleanSinceMs = nowMs;
        if (cap >= MAX_CAP) return;
        double before = cap;
        cap = Math.min(MAX_CAP, cap + RECOVERY);
        save();
        MarketFeed.log("pacer", "clean for " + RECOVERY_INTERVAL_MS / 60_000 + " min: cap "
                + fmt(before) + " -> " + fmt(cap) + "/s");
    }

    // ---- persistence -----------------------------------------------------------------------

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("echestmm-pacing.properties");
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Properties p = new Properties();
        try {
            Path file = path();
            if (Files.isRegularFile(file)) {
                try (InputStream in = Files.newInputStream(file)) { p.load(in); }
            }
        } catch (Throwable ignored) {
        }
        cap = clamp(readDouble(p, "cap", DEFAULT_CAP));
        penalties = (int) readDouble(p, "penalties", 0);
    }

    private static void save() {
        Properties p = new Properties();
        p.setProperty("cap", Double.toString(cap));
        p.setProperty("penalties", Integer.toString(penalties));
        try {
            Path file = path();
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                p.store(out, "EchestMM persistent action pacing ceiling");
            }
        } catch (Throwable ignored) {
        }
    }

    private static double readDouble(Properties p, String key, double fallback) {
        try { return Double.parseDouble(p.getProperty(key, Double.toString(fallback))); }
        catch (RuntimeException ignored) { return fallback; }
    }

    static double clamp(double value) {
        if (!Double.isFinite(value)) return DEFAULT_CAP;
        return Math.max(MIN_CAP, Math.min(MAX_CAP, value));
    }

    static double penaltyOf(double from) {
        return Math.max(MIN_CAP, from - PENALTY);
    }

    static double recoveryOf(double from) {
        return Math.min(MAX_CAP, from + RECOVERY);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
