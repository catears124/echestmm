package dev.parker.echest;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.parker.echest.mixin.DialogScreenAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.ItemBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
/**
 * Passive extraction layer: reads the DonutSMP auction house out of the client's already
 * synchronized container menus, the server-supplied confirmation dialogs, and server chat. This
 * class never sends a packet or a click; {@link EchestEngine} owns every outbound action.
 *
 * <p>Data sources:
 * <ol>
 *   <li>Auction pages - item id, display name, stack size, price and seller for every listing
 *       slot, scanned the instant an inbound container packet has been applied.</li>
 *   <li>Confirmation quotes - the executable price the server shows before you press Yes.</li>
 *   <li>Chat receipts - "You bought/listed N X for $Y", "P bought your N X for $Y", plus market
 *       status lines (already bought, rate limit, listings full).</li>
 * </ol>
 *
 * <p>Observations are published to in-process listeners and appended to plain-text logs under
 * {@code .minecraft/echestmm/}.
 */
public final class MarketFeed implements ClientModInitializer {
    /** One auction listing, from a market page or from the player's own listings screen. */
    public record Listing(
            long observedAtMs,
            int slot,
            String itemId,
            String itemName,
            int count,
            double totalPrice,
            double unitPrice,
            String seller,
            String tooltip) {}

    /** One scan of an open auction page. */
    public record PageScan(
            long observedAtMs,
            int page,
            String search,
            String title,
            String dominantItemId,
            String dominantItemName,
            double purity,
            List<Listing> listings,
            String fingerprint) {}

    /** An executable price read from a confirmation surface; {@code price} is NaN when ambiguous. */
    public record QuoteRead(long observedAtMs, String surface, double price, String source, String evidence) {}

    /** A server-authoritative transaction receipt parsed from chat; kind is BUY, SELL or LIST. */
    public record Receipt(long observedAtMs, String kind, String counterparty, String itemName,
                          int count, double totalPrice, String raw) {}

    /** A non-receipt market status line from chat. */
    public record MarketMessage(long observedAtMs, String kind, String raw) {}

    /** State of the player's own listings screen ({@code /ah} then the chest icon). */
    public record OwnListings(long observedAtMs, String title, int active, int free, int locked,
                              int emptySlots, List<Listing> listings, String fingerprint) {
        public boolean isFull() { return free == 0; }
    }

    private static final List<Consumer<PageScan>> pageListeners = new CopyOnWriteArrayList<>();
    private static final List<Consumer<QuoteRead>> quoteListeners = new CopyOnWriteArrayList<>();
    private static final List<Consumer<Receipt>> receiptListeners = new CopyOnWriteArrayList<>();
    private static final List<Consumer<MarketMessage>> messageListeners = new CopyOnWriteArrayList<>();
    private static final List<Consumer<OwnListings>> ownListingsListeners = new CopyOnWriteArrayList<>();

    public static void onPage(Consumer<PageScan> l) { pageListeners.add(l); }
    public static void onQuote(Consumer<QuoteRead> l) { quoteListeners.add(l); }
    public static void onReceipt(Consumer<Receipt> l) { receiptListeners.add(l); }
    public static void onMessage(Consumer<MarketMessage> l) { messageListeners.add(l); }
    public static void onOwnListings(Consumer<OwnListings> l) { ownListingsListeners.add(l); }

    private static volatile PageScan latestPage;
    private static volatile QuoteRead latestQuote;
    private static volatile OwnListings latestOwnListings;

    public static PageScan latestPage() { return latestPage; }
    public static QuoteRead latestQuote() { return latestQuote; }
    public static OwnListings latestOwnListings() { return latestOwnListings; }

    // ---- parsing ---------------------------------------------------------------------------

    private static final Pattern PRICE_CONTEXT = Pattern.compile(
            "(?i)(?:price|cost|buy|sell|worth|value)[^0-9$]{0,28}\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmbt]?)"
    );
    private static final Pattern DOLLAR_PRICE = Pattern.compile(
            "(?i)\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmbt]?)"
    );
    private static final Pattern FOR_DOLLAR_PRICE = Pattern.compile(
            "(?i)\\bfor\\s+\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmbt]?)\\b"
    );
    private static final Pattern SELLER = Pattern.compile(
            "(?i)(?:seller|listed by|owner)\\s*[:\u00bb>-]?\\s*([^|\u2022]+)"
    );
    private static final Pattern SEARCH = Pattern.compile(
            "(?i)search\\s*:\\s*(.*?)(?:\\s*\\||\\s+page\\s+\\d+|$)"
    );
    private static final Pattern BUY_RECEIPT = Pattern.compile(
            "(?i)\\byou\\s+bought\\s+(\\d+)\\s+(.+?)\\s+for\\s+\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmbt]?)\\b"
    );
    private static final Pattern SELL_RECEIPT = Pattern.compile(
            "(?i)^(.+?)\\s+bought\\s+your\\s+(?:(\\d+)\\s+)?(.+?)\\s+for\\s+\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmbt]?)\\b"
    );
    private static final Pattern LIST_RECEIPT = Pattern.compile(
            "(?i)\\byou\\s+listed\\s+(\\d+)\\s+(.+?)\\s+for\\s+\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmbt]?)\\b"
    );
    private static final Pattern ALREADY_BOUGHT = Pattern.compile("(?i)\\bthis\\s+item\\s+was\\s+already\\s+bought\\b");
    private static final Pattern SPAM_SIGNAL = Pattern.compile(
            "(?i)(spamm(?:ing|ed)?|too\\s+(?:fast|quick)|slow\\s+down|commands?\\s+too\\s+quick|"
                    + "wait\\s+[0-9]+(?:\\.[0-9]+)?\\s*(?:s|sec|secs|second|seconds)\\b)"
    );
    private static final Pattern AREA_MAINTENANCE = Pattern.compile("(?i)area\\s+in\\s+maintenance");
    private static final Pattern OFFLINE_SALE = Pattern.compile("(?i)while\\s+you\\s+were\\s+away");
    /** Donut's reply to {@code /ah sell} when every listing slot is taken. */
    private static final Pattern AH_FULL = Pattern.compile("(?i)too\\s+many\\s+listed\\s+items");
    private static final Pattern NOT_ENOUGH_MONEY = Pattern.compile("(?i)(not\\s+enough\\s+money|can(?:no|')t\\s+afford)");
    /** Donut's {@code /bal} reply, used as the cash budget for sniping and floor lifts. */
    private static final Pattern BALANCE_LINE = Pattern.compile(
            "(?i)balance[^0-9$]{0,16}\\$?\\s*[0-9][0-9,]*(?:\\.[0-9]+)?\\s*[kmbt]?");
    /** Buy-order lifecycle, as recorded: "You ordered 2.5K Obsidian", "…order is complete!". */
    private static final Pattern ORDER_PLACED = Pattern.compile("(?i)\\byou\\s+ordered\\b");
    private static final Pattern ORDER_COMPLETE = Pattern.compile("(?i)order\\s+is\\s+complete");
    private static final Pattern ORDER_DELIVERY = Pattern.compile("(?i)delivered\\s+you\\b");

    /** Slots at the bottom of every chest menu that belong to the player's own inventory. */
    static final int PLAYER_INVENTORY_SLOTS = 36;
    private static TextLogger logger;
    private static String lastPageFingerprint = "";
    private static String lastOwnFingerprint = "";
    private static Screen lastQuoteScreen;
    private static int cachedPriceTooltipLine = -1;
    private static boolean announceOwnReads = false;

    @Override
    public void onInitializeClient() {
        try {
            logger = new TextLogger(FabricLoader.getInstance().getGameDir().resolve("echestmm"));
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize EchestMM logger", e);
        }

        ClientReceiveMessageEvents.GAME.register(MarketFeed::onServerGameMessage);
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> readQuoteIfConfirmScreen(client, screen));
        ClientTickEvents.END_CLIENT_TICK.register(MarketFeed::rescanCurrentScreen);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("echest")
                        .then(literal("dump").executes(ctx -> {
                            dumpOpenScreen(Minecraft.getInstance(), ctx.getSource()::sendFeedback);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(literal("reads").executes(ctx -> {
                            announceOwnReads = !announceOwnReads;
                            ctx.getSource().sendFeedback(Component.literal(
                                    "[EchestMM] own-listing read announcements " + (announceOwnReads ? "ON" : "OFF")));
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(literal("layout").executes(ctx -> {
                            ctx.getSource().sendFeedback(Component.literal(
                                    "[EchestMM] last Your Items read: " + lastOwnLayout));
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(literal("record")
                                .then(literal("on").executes(ctx -> {
                                    ctx.getSource().sendFeedback(Component.literal("[EchestMM] " + FlowRecorder.start()));
                                    return Command.SINGLE_SUCCESS;
                                }))
                                .then(literal("off").executes(ctx -> {
                                    ctx.getSource().sendFeedback(Component.literal("[EchestMM] " + FlowRecorder.stop()));
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .then(literal("mark").then(argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ctx.getSource().sendFeedback(Component.literal("[EchestMM] "
                                            + FlowRecorder.mark(StringArgumentType.getString(ctx, "text"))));
                                    return Command.SINGLE_SUCCESS;
                                })))
                )
        );

        FlowRecorder.init();
        EchestEngine.init();
    }

    // ---- packet hooks ----------------------------------------------------------------------

    public static void onInboundContainerPacket() {
        rescanCurrentScreen(Minecraft.getInstance());
    }

    private static void rescanCurrentScreen(Minecraft client) {
        if (client == null || client.player == null) return;
        Screen screen = client.gui.screen();
        if (!(screen instanceof AbstractContainerScreen<?> container)) return;
        String title = screen.getTitle().getString();
        if (!isAuctionPage(title)) {
            OwnListings own = scanOwnListings(client, container, title, System.currentTimeMillis());
            if (own != null) {
                latestOwnListings = own; // refresh the timestamp even when unchanged, so waiters progress
                if (own.fingerprint.equals(lastOwnFingerprint)) return;
                lastOwnFingerprint = own.fingerprint;
                log("own", own.active + " taken, " + own.free + " free, " + own.locked + " locked | "
                        + lastOwnLayout);
                if (announceOwnReads) {
                    client.player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                            "[EchestMM] your listings: %d taken, %d free%s", own.active, own.free,
                            own.isFull() ? "  -> FULL" : "")));
                }
                for (Consumer<OwnListings> l : ownListingsListeners) safely(() -> l.accept(own));
                return;
            }
            readQuoteIfConfirmScreen(client, screen);
            return;
        }
        PageScan scan = scanAuctionPage(client, container, title, System.currentTimeMillis());
        if (scan == null || scan.fingerprint.equals(lastPageFingerprint)) return;
        lastPageFingerprint = scan.fingerprint;
        latestPage = scan;
        for (Consumer<PageScan> l : pageListeners) safely(() -> l.accept(scan));
    }

    // ---- auction page scan -----------------------------------------------------------------

    static PageScan scanAuctionPage(Minecraft client, AbstractContainerScreen<?> screen, String title, long now) {
        AbstractContainerMenu menu = screen.getMenu();
        List<ItemStack> stacks = menu.getItems();
        int scanSlots = Math.max(0, stacks.size() - PLAYER_INVENTORY_SLOTS);
        if (scanSlots == 0) scanSlots = stacks.size();

        ArrayList<Listing> listings = new ArrayList<>(scanSlots);
        LinkedHashMap<String, Integer> frequencies = new LinkedHashMap<>();
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        StringBuilder fp = new StringBuilder(512).append(title).append('|');

        for (int slot = 0; slot < scanSlots; slot++) {
            ItemStack stack = stacks.get(slot);
            if (stack == null || stack.isEmpty()) continue;
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null) continue;

            List<Component> tip = tooltipLines(client, stack);
            double price = priceFromTooltipLines(tip);
            if (!(price > 0.0) || !Double.isFinite(price)) continue; // filler pane or control button

            String tooltip = joinLines(tip, " | ");
            int count = Math.max(1, stack.getCount());
            String itemId = id.toString();
            listings.add(new Listing(now, slot, itemId, stack.getDisplayName().getString(), count,
                    price, price / count, parseSeller(tooltip), tooltip));
            frequencies.merge(itemId, 1, Integer::sum);
            names.putIfAbsent(itemId, stack.getDisplayName().getString());
            fp.append(slot).append(':').append(itemId).append(':').append(count).append(':')
                    .append(Math.round(price)).append(';');
        }
        if (listings.isEmpty()) return null;

        String dominantId = "";
        int dominantCount = 0;
        for (Map.Entry<String, Integer> e : frequencies.entrySet()) {
            if (e.getValue() > dominantCount) {
                dominantId = e.getKey();
                dominantCount = e.getValue();
            }
        }
        return new PageScan(now, parsePage(title), cleanSearchText(parseSearch(title)), title,
                dominantId, names.getOrDefault(dominantId, dominantId),
                dominantCount / (double) listings.size(), List.copyOf(listings), fp.toString());
    }

    // ---- own listings screen ---------------------------------------------------------------

    /**
     * Reads the player's own listings screen.
     *
     * <p>This deliberately makes no assumption about how many rows the screen has or where the
     * free slots sit. The first version scanned a fixed 18 slots because that is what one account
     * happened to show; an account with more listing slots reported "18 taken, 0 free" while whole
     * rows of free slots sat below the window, and the engine concluded the auction house was
     * full. Every slot in the container's own section is classified instead:
     *
     * <ul>
     *   <li>a glass pane whose name mentions listing or selling is a <b>free</b> slot;</li>
     *   <li>a red pane, or one whose name mentions locking or unlocking, is <b>locked</b>;</li>
     *   <li>anything carrying a price is an <b>active listing</b>;</li>
     *   <li>anything else - navigation arrows, heads, decorative filler - is ignored.</li>
     * </ul>
     */
    static OwnListings scanOwnListings(Minecraft client, AbstractContainerScreen<?> screen, String title, long now) {
        AbstractContainerMenu menu = screen.getMenu();
        List<ItemStack> stacks = menu.getItems();
        int topSlots = Math.max(0, stacks.size() - PLAYER_INVENTORY_SLOTS);
        if (topSlots <= 0) return null;

        int free = 0;
        int locked = 0;
        int empty = 0;
        int ignored = 0;
        boolean sawListPane = false;
        ArrayList<Listing> listings = new ArrayList<>();
        StringBuilder fp = new StringBuilder(256).append(title).append('|');
        for (int slot = 0; slot < topSlots; slot++) {
            ItemStack stack = stacks.get(slot);
            if (stack == null || stack.isEmpty()) { empty++; fp.append('_'); continue; }
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            String idText = id == null ? "" : id.toString();
            List<Component> tip = tooltipLines(client, stack);
            double price = priceFromTooltipLines(tip);
            boolean hasPrice = price > 0.0 && Double.isFinite(price);

            switch (classifyOwnSlot(idText, stack.getDisplayName().getString(), hasPrice)) {
                case FREE -> { free++; sawListPane = true; fp.append('f'); }
                case LOCKED -> { locked++; fp.append('r'); }
                case OTHER -> { ignored++; fp.append('.'); }
                case LISTING -> {
                    String tooltip = joinLines(tip, " | ");
                    int count = Math.max(1, stack.getCount());
                    listings.add(new Listing(now, slot, idText, stack.getDisplayName().getString(),
                            count, price, price / count, parseSeller(tooltip), tooltip));
                    fp.append(slot).append(':').append(idText).append(':').append(count).append(':')
                            .append(Math.round(price)).append(';');
                }
            }
        }

        // Accept the screen only on positive evidence: a free-slot pane, or at least one priced
        // listing on a screen whose title names it. Otherwise this is some other chest.
        boolean titleMatch = title != null && title.toLowerCase(Locale.ROOT).contains("your");
        if (!sawListPane && !(titleMatch && !listings.isEmpty())) return null;

        // Some layouts leave a free slot genuinely empty instead of filling it with a "List" pane.
        // Treating those as taken is what produced a false "auction house full", so empties count
        // as free when the screen showed no free-slot panes at all.
        int freeSlots = free;
        String note = "";
        if (free == 0 && empty > 0) {
            freeSlots = empty;
            note = " (counting " + empty + " empty slots as free: no List panes on this layout)";
        }
        lastOwnLayout = topSlots + " slots: " + listings.size() + " listed, " + freeSlots + " free, "
                + locked + " locked, " + empty + " empty, " + ignored + " other" + note;
        return new OwnListings(now, title, listings.size(), freeSlots, locked, empty,
                List.copyOf(listings), fp.toString());
    }

    /** Last own-listings layout seen, for {@code /echest layout}. */
    private static volatile String lastOwnLayout = "no own-listings screen read yet";

    /** What one slot of the own-listings screen represents. */
    enum SlotKind { FREE, LOCKED, LISTING, OTHER }

    /**
     * Classifies one slot by its item id, display name and whether its tooltip carries a price.
     * Position is deliberately not an input: the screen's row count varies by account.
     */
    static SlotKind classifyOwnSlot(String idText, String displayName, boolean hasPrice) {
        String id = idText == null ? "" : idText;
        String name = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
        boolean pane = id.endsWith("_stained_glass_pane") || id.equals("minecraft:glass_pane");
        if (id.equals("minecraft:red_stained_glass_pane") || name.contains("locked") || name.contains("unlock")) {
            return SlotKind.LOCKED;
        }
        if (pane && (name.contains("list") || name.contains("sell") || name.contains("empty")
                || name.contains("available") || name.contains("free"))) {
            return SlotKind.FREE;
        }
        return hasPrice ? SlotKind.LISTING : SlotKind.OTHER;
    }

    public static String lastOwnLayout() {
        return lastOwnLayout;
    }

    static String describeSlots(AbstractContainerMenu menu, int n) {
        List<ItemStack> stacks = menu.getItems();
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        int limit = Math.min(n, stacks.size());
        for (int i = 0; i < limit; i++) {
            ItemStack st = stacks.get(i);
            String key;
            if (st == null || st.isEmpty()) key = "(empty)";
            else {
                Identifier id = BuiltInRegistries.ITEM.getKey(st.getItem());
                key = (id == null ? "?" : id.toString()) + " '" + st.getDisplayName().getString() + "'";
            }
            counts.merge(key, 1, Integer::sum);
        }
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (!b.isEmpty()) b.append("; ");
            b.append(e.getValue()).append("x ").append(e.getKey());
        }
        return b.toString();
    }

    static void dumpOpenScreen(Minecraft client, Consumer<Component> feedback) {
        Screen screen = client.gui.screen();
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            feedback.accept(Component.literal("[EchestMM] no container screen is open"));
            return;
        }
        AbstractContainerMenu menu = container.getMenu();
        List<ItemStack> stacks = menu.getItems();
        int top = Math.max(0, stacks.size() - PLAYER_INVENTORY_SLOTS);
        if (top == 0) top = stacks.size();
        String title = screen.getTitle().getString();
        feedback.accept(Component.literal("[EchestMM] screen \"" + title + "\": " + top + " top slots, "
                + stacks.size() + " total"));
        StringBuilder file = new StringBuilder("# ").append(Instant.now()).append(" title=").append(title).append('\n');
        for (int i = 0; i < top; i++) {
            ItemStack st = stacks.get(i);
            String line;
            if (st == null || st.isEmpty()) line = i + ": (empty)";
            else {
                Identifier id = BuiltInRegistries.ITEM.getKey(st.getItem());
                line = i + ": " + st.getCount() + "x " + (id == null ? "?" : id.toString())
                        + " '" + st.getDisplayName().getString() + "' | " + joinLines(tooltipLines(client, st), " | ");
            }
            file.append(line).append('\n');
            if (st != null && !st.isEmpty()) {
                feedback.accept(Component.literal("  " + (line.length() > 200 ? line.substring(0, 200) + "..." : line)));
            }
        }
        if (logger != null) logger.append(logger.dumps, file.toString());
        feedback.accept(Component.literal("[EchestMM] full dump written to echestmm/screen_dumps.txt"));
    }

    // ---- confirmation quotes ---------------------------------------------------------------

    private static void readQuoteIfConfirmScreen(Minecraft client, Screen screen) {
        if (client == null || client.player == null || screen == null || screen == lastQuoteScreen) return;
        QuoteRead read;
        if (screen instanceof DialogScreen<?> dialog) {
            read = readDialogQuote(client, dialog);
        } else if (screen instanceof AbstractContainerScreen<?> container
                && !isAuctionPage(screen.getTitle().getString())) {
            read = readContainerQuote(client, container);
        } else {
            return;
        }
        lastQuoteScreen = screen;
        latestQuote = read;
        for (Consumer<QuoteRead> l : quoteListeners) safely(() -> l.accept(read));
    }

    /** Reads the server-supplied dialog body: title, plain messages, and any embedded item. */
    public static QuoteRead readDialogQuote(Minecraft client, DialogScreen<?> screen) {
        long now = System.currentTimeMillis();
        StringBuilder rendered = new StringBuilder();
        List<Double> itemQuotes = new ArrayList<>();
        try {
            Dialog dialog = ((DialogScreenAccessor) (Object) screen).echest$getDialog();
            if (dialog != null && dialog.common() != null) {
                if (dialog.common().title() != null) rendered.append(dialog.common().title().getString()).append(' ');
                for (DialogBody body : dialog.common().body()) {
                    if (body instanceof PlainMessage plain) {
                        rendered.append(plain.contents().getString()).append(' ');
                    } else if (body instanceof ItemBody itemBody) {
                        itemBody.description().ifPresent(d -> rendered.append(d.contents().getString()).append(' '));
                        try {
                            ItemStack quoteStack = itemBody.item().create();
                            if (quoteStack != null && !quoteStack.isEmpty()) {
                                String itemText = joinLines(tooltipLines(client, quoteStack), " ");
                                rendered.append(itemText).append(' ');
                                Double q = parsePrice(itemText);
                                if (q != null && q > 0.0 && Double.isFinite(q)) itemQuotes.add(q);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Accessor failure falls through to the title-only read below.
        }
        if (rendered.isEmpty() && screen.getTitle() != null) rendered.append(screen.getTitle().getString());
        return resolveQuote(now, "dialog", rendered.toString().trim(), itemQuotes);
    }

    /** Reads a confirm chest: title plus every item tooltip in the top section. */
    public static QuoteRead readContainerQuote(Minecraft client, AbstractContainerScreen<?> screen) {
        long now = System.currentTimeMillis();
        List<ItemStack> stacks = screen.getMenu().getItems();
        int limit = Math.max(0, stacks.size() - PLAYER_INVENTORY_SLOTS);
        if (limit == 0) limit = stacks.size();
        StringBuilder rendered = new StringBuilder();
        if (screen.getTitle() != null) rendered.append(screen.getTitle().getString()).append(' ');
        List<Double> itemQuotes = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            ItemStack stack = stacks.get(i);
            if (stack == null || stack.isEmpty()) continue;
            String itemText = joinLines(tooltipLines(client, stack), " ");
            if (itemText.isBlank()) itemText = stack.getDisplayName().getString();
            rendered.append(itemText).append(' ');
            Double q = parsePrice(itemText);
            if (q != null && q > 0.0 && Double.isFinite(q)) itemQuotes.add(q);
        }
        return resolveQuote(now, "container", rendered.toString().trim(), itemQuotes);
    }

    private static QuoteRead resolveQuote(long now, String surface, String text, List<Double> itemQuotes) {
        double itemQuote = uniqueConsistentPrice(itemQuotes);
        if (Double.isFinite(itemQuote)) return new QuoteRead(now, surface, itemQuote, "item", text);
        if (!itemQuotes.isEmpty()) return new QuoteRead(now, surface, Double.NaN, "ambiguous_items", text);
        Double forPrice = firstPatternPrice(FOR_DOLLAR_PRICE, text);
        if (forPrice != null) return new QuoteRead(now, surface, forPrice, "for", text);
        Double contextual = firstPatternPrice(PRICE_CONTEXT, text);
        if (contextual != null) return new QuoteRead(now, surface, contextual, "context", text);
        double single = singleDollarPrice(text);
        if (Double.isFinite(single)) return new QuoteRead(now, surface, single, "single_dollar", text);
        return new QuoteRead(now, surface, Double.NaN, "no_unique_price", text);
    }

    // ---- chat ------------------------------------------------------------------------------

    private static void onServerGameMessage(Component message, boolean overlay) {
        if (message == null) return;
        String text = message.getString();
        if (text == null || text.isBlank()) return;
        // Our own chat output comes back through this event; parsing it would loop forever.
        if (text.contains("[EchestMM]")) return;
        long now = System.currentTimeMillis();

        Matcher buy = BUY_RECEIPT.matcher(text);
        if (buy.find()) {
            emitReceipt(new Receipt(now, "BUY", "", normalizeItemName(buy.group(2)),
                    Integer.parseInt(buy.group(1)), compactNumber(buy.group(3), buy.group(4)), text));
            return;
        }
        Matcher listed = LIST_RECEIPT.matcher(text);
        if (listed.find()) {
            emitReceipt(new Receipt(now, "LIST", "", normalizeItemName(listed.group(2)),
                    Integer.parseInt(listed.group(1)), compactNumber(listed.group(3), listed.group(4)), text));
            return;
        }
        Matcher sold = SELL_RECEIPT.matcher(text);
        if (sold.find()) {
            // Donut's sale line often omits the quantity ("X bought your Obsidian for $22K").
            // Reporting that as 1 made a 64-unit stack desk discard its own sold-side history, so
            // an absent count is recorded as 0, meaning "unspecified", not "one".
            int count = sold.group(2) == null || sold.group(2).isBlank() ? 0 : Integer.parseInt(sold.group(2));
            emitReceipt(new Receipt(now, "SELL", sold.group(1).trim(), normalizeItemName(sold.group(3)),
                    count, compactNumber(sold.group(4), sold.group(5)), text));
            return;
        }

        String kind = null;
        if (AH_FULL.matcher(text).find()) kind = "AH_FULL";
        else if (ALREADY_BOUGHT.matcher(text).find()) kind = "ALREADY_BOUGHT";
        else if (SPAM_SIGNAL.matcher(text).find()) kind = "RATE_LIMIT";
        else if (NOT_ENOUGH_MONEY.matcher(text).find()) kind = "NO_FUNDS";
        else if (AREA_MAINTENANCE.matcher(text).find()) kind = "MAINTENANCE";
        else if (OFFLINE_SALE.matcher(text).find()) kind = "OFFLINE_SALE";
        else if (BALANCE_LINE.matcher(text).find()) kind = "BALANCE";
        else if (ORDER_PLACED.matcher(text).find()) kind = "ORDER_PLACED";
        else if (ORDER_COMPLETE.matcher(text).find()) kind = "ORDER_COMPLETE";
        else if (ORDER_DELIVERY.matcher(text).find()) kind = "ORDER_DELIVERY";
        if (kind == null) return;
        MarketMessage mm = new MarketMessage(now, kind, text);
        log("msg", kind + " \"" + text + "\"");
        for (Consumer<MarketMessage> l : messageListeners) safely(() -> l.accept(mm));
    }

    private static void emitReceipt(Receipt r) {
        log("receipt", r.kind + " " + r.count + "x " + r.itemName + " @ " + Math.round(r.totalPrice)
                + (r.counterparty.isBlank() ? "" : " (" + r.counterparty + ")"));
        for (Consumer<Receipt> l : receiptListeners) safely(() -> l.accept(r));
    }

    // ---- tooltip and price helpers ---------------------------------------------------------

    private static List<Component> tooltipLines(Minecraft client, ItemStack stack) {
        try {
            List<Component> tip = Screen.getTooltipFromItem(client, stack);
            return tip == null ? List.of() : tip;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String joinLines(List<Component> lines, String separator) {
        StringBuilder b = new StringBuilder(128);
        for (int i = 0, n = lines.size(); i < n; i++) {
            String line = lines.get(i).getString();
            if (line.isBlank()) continue;
            if (!b.isEmpty()) b.append(separator);
            b.append(line);
        }
        return b.toString();
    }

    private static double priceFromTooltipLines(List<Component> lines) {
        if (cachedPriceTooltipLine >= 0 && cachedPriceTooltipLine < lines.size()) {
            double cached = fastDollarPrice(lines.get(cachedPriceTooltipLine).getString());
            if (cached > 0.0 && Double.isFinite(cached)) return cached;
        }
        for (int i = 0; i < lines.size(); i++) {
            double price = fastDollarPrice(lines.get(i).getString());
            if (price > 0.0 && Double.isFinite(price)) {
                cachedPriceTooltipLine = i;
                return price;
            }
        }
        for (int i = 0; i < lines.size(); i++) {
            Double price = parsePrice(lines.get(i).getString());
            if (price != null && price > 0.0 && Double.isFinite(price)) {
                cachedPriceTooltipLine = i;
                return price;
            }
        }
        return Double.NaN;
    }

    /** Hand-rolled "$1,234.5k" reader; no regex, no allocation. */
    static double fastDollarPrice(String s) {
        if (s == null || s.isEmpty()) return Double.NaN;
        int dollar = s.indexOf('$');
        if (dollar < 0) return Double.NaN;
        int i = dollar + 1;
        int n = s.length();
        while (i < n && Character.isWhitespace(s.charAt(i))) i++;
        double value = 0.0;
        double decimalScale = 0.0;
        boolean any = false;
        boolean decimal = false;
        while (i < n) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                any = true;
                int digit = c - '0';
                if (!decimal) {
                    value = value * 10.0 + digit;
                } else {
                    if (decimalScale == 0.0) decimalScale = 0.1;
                    value += digit * decimalScale;
                    decimalScale *= 0.1;
                }
                i++;
                continue;
            }
            if (c == ',') { i++; continue; }
            if (c == '.' && !decimal) { decimal = true; i++; continue; }
            break;
        }
        if (!any) return Double.NaN;
        while (i < n && Character.isWhitespace(s.charAt(i))) i++;
        if (i < n) {
            char suffix = Character.toLowerCase(s.charAt(i));
            if (suffix == 'k') value *= 1_000.0;
            else if (suffix == 'm') value *= 1_000_000.0;
            else if (suffix == 'b') value *= 1_000_000_000.0;
            else if (suffix == 't') value *= 1_000_000_000_000.0;
        }
        return value;
    }

    static Double parsePrice(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher contextual = PRICE_CONTEXT.matcher(text);
        if (contextual.find()) return compactNumber(contextual.group(1), contextual.group(2));
        Matcher dollar = DOLLAR_PRICE.matcher(text);
        if (dollar.find()) return compactNumber(dollar.group(1), dollar.group(2));
        return null;
    }

    static double compactNumber(String raw, String suffix) {
        double x = Double.parseDouble(raw.replace(",", ""));
        return x * switch (suffix == null ? "" : suffix.toLowerCase(Locale.ROOT)) {
            case "k" -> 1_000.0;
            case "m" -> 1_000_000.0;
            case "b" -> 1_000_000_000.0;
            case "t" -> 1_000_000_000_000.0;
            default -> 1.0;
        };
    }

    private static Double firstPatternPrice(Pattern pattern, String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = pattern.matcher(text);
        if (!m.find()) return null;
        try {
            double x = compactNumber(m.group(1), m.group(2));
            return x > 0.0 && Double.isFinite(x) ? x : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static double singleDollarPrice(String text) {
        if (text == null || text.isBlank()) return Double.NaN;
        Matcher m = DOLLAR_PRICE.matcher(text);
        double only = Double.NaN;
        while (m.find()) {
            double x;
            try { x = compactNumber(m.group(1), m.group(2)); }
            catch (RuntimeException ignored) { continue; }
            if (!(x > 0.0) || !Double.isFinite(x)) continue;
            if (!Double.isFinite(only)) only = x;
            else if (Math.abs(only - x) > priceTolerance(Math.max(only, x))) return Double.NaN;
        }
        return only;
    }

    private static double uniqueConsistentPrice(List<Double> prices) {
        if (prices == null || prices.isEmpty()) return Double.NaN;
        double first = prices.getFirst();
        if (!(first > 0.0) || !Double.isFinite(first)) return Double.NaN;
        for (int i = 1; i < prices.size(); i++) {
            double x = prices.get(i);
            if (!(x > 0.0) || !Double.isFinite(x)) continue;
            if (Math.abs(first - x) > priceTolerance(Math.max(first, x))) return Double.NaN;
        }
        return first;
    }

    /** Compact suffixes ("$12.3k") round, so two renderings of one price may differ slightly. */
    private static double priceTolerance(double price) {
        double tick = price >= 10_000 ? 100.0 : (price >= 1_000 ? 10.0 : 1.0);
        return Math.max(0.5, tick * 0.51);
    }

    static String parseSeller(String tooltip) {
        Matcher m = SELLER.matcher(tooltip == null ? "" : tooltip);
        return m.find() ? m.group(1).trim() : "";
    }

    // ---- title helpers ---------------------------------------------------------------------

    public static boolean isAuctionPage(String title) {
        return containsAsciiIgnoreCase(title, "auction") && parsePage(title) > 0;
    }

    private static boolean containsAsciiIgnoreCase(String text, String needle) {
        if (text == null || needle == null || needle.isEmpty() || text.length() < needle.length()) return false;
        for (int i = 0, stop = text.length() - needle.length(); i <= stop; i++) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) return true;
        }
        return false;
    }

    /** Page number from a title like "Auction House | Page 3", or -1. */
    static int parsePage(String title) {
        if (title == null) return -1;
        int n = title.length();
        for (int i = 0; i + 4 <= n; i++) {
            if (!title.regionMatches(true, i, "page", 0, 4)) continue;
            int j = i + 4;
            while (j < n && !Character.isDigit(title.charAt(j))) j++;
            if (j >= n) return -1;
            int value = 0;
            boolean any = false;
            while (j < n) {
                char c = title.charAt(j);
                if (c < '0' || c > '9') break;
                any = true;
                value = value * 10 + (c - '0');
                if (value > 999) return -1;
                j++;
            }
            return any ? value : -1;
        }
        return -1;
    }

    static String parseSearch(String title) {
        Matcher m = SEARCH.matcher(title == null ? "" : title);
        return m.find() ? m.group(1).trim() : "";
    }

    static String cleanSearchText(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        while (s.length() >= 2 && ((s.startsWith("[") && s.endsWith("]"))
                || (s.startsWith("(") && s.endsWith(")")))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s.replaceAll("\u00a7[0-9A-FK-ORa-fk-or]", "").trim();
    }

    private static String normalizeItemName(String text) {
        if (text == null) return "";
        return text.replaceAll("\u00a7[0-9A-FK-ORa-fk-or]", "").replaceAll("\\s+", " ").trim();
    }

    /** Appends one line to {@code echestmm/echest.log}. */
    static void log(String channel, String line) {
        if (logger != null) logger.append(logger.events, Instant.now() + " [" + channel + "] " + line + "\n");
    }

    private static void safely(Runnable r) {
        try { r.run(); } catch (Throwable ignored) {}
    }

    /** Appends plain text on a single background thread; the client thread never blocks on disk. */
    private static final class TextLogger {
        private final Path events;
        private final Path dumps;
        private final ExecutorService writer;

        TextLogger(Path directory) throws IOException {
            Files.createDirectories(directory);
            this.events = directory.resolve("echest.log");
            this.dumps = directory.resolve("screen_dumps.txt");
            this.writer = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "EchestMM-Logger");
                t.setDaemon(true);
                return t;
            });
        }

        void append(Path path, String text) {
            writer.execute(() -> {
                try {
                    Files.writeString(path, text, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
