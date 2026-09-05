package dev.parker.echest;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads realised trade prices back out of the mod's own log.
 *
 * <p>Every receipt the server sent has already been recorded, which makes the log a record of
 * prices that actually executed - not opinions about what a fair price might be. Desk parameters
 * are derived from it rather than hardcoded, so they describe this account's market and move as
 * the market moves.
 */
public final class History {
    /** One receipt line: kind, stack size, item, price. */
    private static final Pattern RECEIPT = Pattern.compile(
            "\\[receipt] (BUY|SELL|LIST) (\\d+)x (.+?) @ (\\d+)");

    /** Read at most this much of the tail; the log grows without bound. */
    private static final long TAIL_BYTES = 4L * 1024L * 1024L;

    /** Realised prices for one item and stack size, oldest first. */
    public record Samples(String itemId, int unitSize, List<Long> bought, List<Long> sold) {
        public int size() { return bought.size() + sold.size(); }

        /** Every realised price, whichever side it came from. */
        public List<Long> all() {
            List<Long> out = new ArrayList<>(bought.size() + sold.size());
            out.addAll(bought);
            out.addAll(sold);
            return out;
        }
    }

    private History() {}

    /** Parses one log line, or returns null. Package-private so the format itself is testable. */
    static long[] parseReceipt(String line, String itemPath, int unitSize) {
        if (line == null) return null;
        Matcher m = RECEIPT.matcher(line);
        if (!m.find()) return null;
        String name = m.group(3).trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (!name.equals(itemPath)) return null;
        int count;
        long price;
        try {
            count = Integer.parseInt(m.group(2));
            price = Long.parseLong(m.group(4));
        } catch (NumberFormatException ignored) {
            return null;
        }
        if (count != unitSize || price <= 0) return null;
        // kind: 0 = bought, 1 = sold. A LIST is an intention, not a fill, so it is skipped.
        return switch (m.group(1)) {
            case "BUY" -> new long[]{0, price};
            case "SELL" -> new long[]{1, price};
            default -> null;
        };
    }

    /** Collects realised prices for {@code itemId} at {@code unitSize} from log lines. */
    static Samples collect(Iterable<String> lines, String itemId, int unitSize, int maxSamples) {
        String path = itemId.substring(itemId.indexOf(':') + 1);
        List<Long> bought = new ArrayList<>();
        List<Long> sold = new ArrayList<>();
        for (String line : lines) {
            long[] hit = parseReceipt(line, path, unitSize);
            if (hit == null) continue;
            List<Long> target = hit[0] == 0 ? bought : sold;
            target.add(hit[1]);
            if (maxSamples > 0 && target.size() > maxSamples) target.removeFirst();
        }
        return new Samples(itemId, unitSize, bought, sold);
    }

    /** Reads the tail of {@code echestmm/echest.log} and collects realised prices from it. */
    public static Samples load(String itemId, int unitSize, int maxSamples) {
        Path log = FabricLoader.getInstance().getGameDir().resolve("echestmm").resolve("echest.log");
        List<String> lines = new ArrayList<>();
        try {
            if (Files.isRegularFile(log)) {
                long size = Files.size(log);
                try (SeekableByteChannel channel = Files.newByteChannel(log, StandardOpenOption.READ)) {
                    if (size > TAIL_BYTES) channel.position(size - TAIL_BYTES);
                    try (BufferedReader reader = new BufferedReader(
                            Channels.newReader(channel, StandardCharsets.UTF_8))) {
                        if (size > TAIL_BYTES) reader.readLine();   // drop a partial line
                        String line;
                        while ((line = reader.readLine()) != null) lines.add(line);
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return collect(lines, itemId, unitSize, maxSamples);
    }

    /** Linear-interpolated quantile of a price list, or {@code Long.MIN_VALUE} when empty. */
    public static long quantile(List<Long> values, double q) {
        if (values == null || values.isEmpty()) return Long.MIN_VALUE;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(null);
        if (sorted.size() == 1) return sorted.getFirst();
        double pos = Math.max(0.0, Math.min(1.0, q)) * (sorted.size() - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) return sorted.get(lo);
        double t = pos - lo;
        return Math.round(sorted.get(lo) * (1.0 - t) + sorted.get(hi) * t);
    }
}
