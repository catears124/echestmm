package dev.parker.echest;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * The only class that sends anything to the server, and the only one that spends pacing budget.
 *
 * <p>It runs one desk at a time. A desk is an item, a listing unit size, and which sides are
 * enabled:
 *
 * <ul>
 *   <li><b>Ender chests</b> - sell side only, one unit per listing. Stacks are split a unit at a
 *       time and listed at the liquidity price.</li>
 *   <li><b>Obsidian</b> - both sides, a full stack per listing. Stacks rest high on the ask while
 *       a bid ladder walks up from far below the floor, so acquisition happens at the bottom of
 *       the range instead of at a floor that rises as you lift it.</li>
 * </ul>
 *
 * <p>Every command, slot click and dialog press passes through {@link ActionPacer}. Reads are
 * cached, and the market page is reused while it is fresh rather than re-searched, because an open
 * page keeps updating itself from inbound packets for free.
 */
public final class EchestEngine {
    private static final int HOTBAR_START = 37;   // hotbar key 2; key 1 (slot 36) is never touched
    private static final int HOTBAR_END = 45;     // exclusive
    private static final int MAIN_START = 9;
    private static final int MAIN_END = 36;       // exclusive

    private static final int CLICK_INTERVAL_TICKS = 2;
    private static final int CLICK_SETTLE_TICKS = 5;
    private static final int MAX_CURSOR_FIXES = 2;
    private static final int SCREEN_TIMEOUT_TICKS = 60;
    private static final int SOLD_TIMEOUT_TICKS = 80;
    private static final int FULL_RECHECK_TICKS = 200;
    private static final int MARKET_RECHECK_TICKS = 600;
    /** With no stock, how often the book is re-read so a sweep is priced on current data. */
    private static final int MARKET_REFRESH_WHILE_EMPTY_TICKS = 100;
    /** How far above realised sale prices a book quote may be trusted as a resale anchor. */
    private static final long RESALE_OPTIMISM_LIMIT = 3L;
    /** Price levels printed by {@code /echestmm book}. */
    private static final int BOOK_LADDER_LINES = 12;
    private static final int OWN_REREAD_EVERY = 25;
    private static final int BALANCE_REFRESH_TICKS = 2400;

    /** How long a posted order stands before the book is re-planned. */
    private static final long ORDER_REPLAN_MS = 60_000L;
    /** Acquire through buy orders rather than clicks. */
    private static boolean ordering = true;
    /** Modelled profit an order sweep must clear before it is worth posting. */
    private static long minOrderProfit = 25_000L;
    private static long lastOrderMs;
    private static Sweep.Plan lastSweep = Sweep.Plan.no("not evaluated");
    /**
     * Hard ceiling on what one buy order may commit.
     *
     * <p>The risk limit on the obsidian desk. Orders fill fast and in dozens of pieces - 640 items
     * arrived from twelve sellers in eighteen seconds - so an oversized order becomes an inventory
     * problem before it becomes a pricing one.
     */
    private static long maxOrderCost = 1_000_000L;
    /** Items and dollars acquired through orders, which is a real basis and belongs in the cost. */
    private static long orderedItems;
    private static long orderedCost;

    /** An open auction page younger than this is reused instead of re-searched. */
    private static final long PAGE_FRESH_MS = 1_500L;
    /** Weight charged for one inventory shuffling click; three of them cost one interaction. */
    private static final double CLICK_WEIGHT = 0.34;

    /** Extracts the amount from "You have $ 17,567,146" - the real text, not a guessed one. */
    private static final Pattern BALANCE = Pattern.compile(
            "(?i)\\byou\\s+have\\s*\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmbt]?)\\b");
    /** How long a server-side container may stay open before a close packet is forced. */
    private static final long CONTAINER_STALL_MS = 3_000L;
    private static final int MAX_CONTAINER_CLOSE_ATTEMPTS = 3;
    /** Ticks allowed for a disconnect to reveal itself as involuntary. */
    private static final long DISCONNECT_CLASSIFY_TICKS = 60L;

    /** Discovery may probe up to this multiple of the realised-price anchor, never past it. */
    private static final long ASK_DISCOVERY_MAX_MULTIPLE = 8L;
    /** How often a squeeze is re-planned, and how long one plan stays in force. */
    private static final long SQUEEZE_REPLAN_MS = 30_000L;
    enum State {
        OFF, PAUSED,
        CHECK_OWN, WAIT_OWN_AH, WAIT_OWN_SCAN,
        CHECK_MARKET, WAIT_MARKET,
        PREPARE_SELL, SELL_CMD, WAIT_CONFIRM, WAIT_SOLD, WAIT_FULL,
        BUY_OPEN, BUY_WAIT_PAGE, BUY_CONFIRM, BUY_SETTLE
    }

    private record Click(int slot, int button, ContainerInput input) {}

    /** A listing of ours that is live, kept to measure hold time when it sells. */
    private record LiveListing(long price, long listedAtMs, int queueAhead) {}

    // ---- desk configuration ----------------------------------------------------------------
    private static String itemId = "minecraft:ender_chest";
    private static String searchTerm = "ender chest";
    private static int unitSize = 1;              // items per listing: 1 for echests, 64 for obsidian
    private static boolean selling = true;
    private static boolean sniping = true;
    private static boolean lifting = true;
    private static boolean acquiring = false;     // bid ladder; the obsidian side
    private static Liquidity.Config cfg = Liquidity.Config.defaults();

    // bid ladder
    private static long bidStart = 25_000;
    private static long bidStep = 500;
    private static double minSpreadPct = 0.25;
    private static long probeSeconds = 20;
    private static int maxHoldUnits = 20;         // stop acquiring above this many listings' worth
    private static long currentBid;
    private static long lastProbeMs;
    private static long lastAcquireFillMs;
    private static int acquired;
    private static long acquiredCost;
    private static boolean lastProbeFilled;

    private static boolean debug = true;

    // ---- runtime ---------------------------------------------------------------------------
    private static State state = State.OFF;
    private static final ArrayDeque<Click> queue = new ArrayDeque<>();
    private static long ticks;
    private static long requestMs;
    private static int waited;
    private static int cooldown;
    private static int retries;
    private static int cursorFixes;
    private static long lastContainerCloseTick = -1_000_000L;
    private static String pauseReason = "";
    private static final int MAX_RETRIES = 3;

    private static int sellSlot = -1;
    private static long currentPrice;
    private static int currentQueueAhead;
    private static int listed;
    private static int sold;
    private static long grossSold;
    private static boolean listReceiptSeen;

    private static int bought;
    private static long grossBought;
    private static int buySlot = -1;
    private static long buyMaxPay;
    private static String buyReason = "";
    private static long buyBudgetRemaining;
    private static long discoveredAsk;
    private static long lastFillAtMs;
    private static Campaign.Plan squeeze;
    private static long squeezeUntilMs;
    private static long lastSqueezePlanMs;
    private static boolean squeezing = false;

    private static final TreeMap<Long, Integer> marketUnits = new TreeMap<>();
    /**
     * Every visible lot at its per-item price, whatever its size.
     *
     * <p>Kept separately from {@link #marketCounts} because the two sides of the desk compare
     * different things: acquisition wants the cheapest item anywhere in the book, while listing
     * may only be compared against lots the size of ours.
     */
    private static final TreeMap<Long, Integer> marketAnyLot = new TreeMap<>();
    private static int lastComparableLots;
    /** Pages folded into the current book read. */
    private static int marketPagesRead;
    /** How deep to page looking for lots our own size. */
    private static final int MAX_MARKET_PAGES = 6;
    private static int lastAnyLots;
    private static long currentUnitPrice;
    private static long bidCeiling;
    private static int localFree = -1;
    private static int salesSinceOwnRead;
    private static boolean ownDirty;
    private static final TreeMap<Long, Integer> ownPriceCounts = new TreeMap<>();
    private static final List<LiveListing> liveListings = new ArrayList<>();
    private static final List<Liquidity.Fill> fills = new ArrayList<>();
    private static double clearRate = Double.NaN;

    private static long lastMarketTick = -1;
    private static final TreeMap<Long, Integer> marketCounts = new TreeMap<>();
    private static String selfName = "";
    private static long cash = -1;
    private static long lastBalanceTick = -1_000_000L;
    private static boolean balanceRequested;
    private static long balanceRequestedAtMs;
    /** How long a /bal request waits for a reply before it is allowed to retry. */
    private static final long BALANCE_REPLY_TIMEOUT_MS = 15_000L;

    private static Desk.Calibration lastCalibration;
    private static Liquidity.Quote lastQuote;
    private static Liquidity.LiftPlan lastLift = Liquidity.LiftPlan.no("not evaluated");
    private static Liquidity.BidPlan lastBid = new Liquidity.BidPlan(false, 0, "not evaluated");
    private static long containerStuckSinceMs;
    private static int containerCloseAttempts;

    private static long disconnectCheckUntilTick;

    /**
     * Panic switch, default {@code ,} and rebindable under Options / Controls / EchestMM.
     *
     * <p>Watched two ways on purpose. A {@link KeyMapping} only fires when no screen is open, and
     * this engine spends most of its time driving auction screens - which is exactly when you want
     * to stop it. So the key is also handled on every screen that opens.
     */
    private static final KeyMapping.Category PANIC_CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("echestmm", "controls"));
    private static KeyMapping panicKey;
    /**
     * Dump and collect keys, default {@code .} and {@code ;}.
     *
     * <p>These exist because chat is unreachable with a container open - which is precisely when
     * both are useful. Dumping the auction page and collecting from an order screen are things you
     * do <em>while looking at that screen</em>, so they cannot be commands.
     */
    private static KeyMapping dumpKey;
    private static KeyMapping collectKey;

    private static void registerPanicKey() {
        panicKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.echestmm.panic",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_COMMA,
                PANIC_CATEGORY));
        dumpKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.echestmm.dump",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_PERIOD,
                PANIC_CATEGORY));
        collectKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.echestmm.collect",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_SEMICOLON,
                PANIC_CATEGORY));
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) ->
                ScreenKeyboardEvents.afterKeyPress(screen).register((s, event) -> {
                    if (panicKey != null && panicKey.matches(event)) togglePanic(client);
                    else if (dumpKey != null && dumpKey.matches(event)) dumpHere(client);
                    else if (collectKey != null && collectKey.matches(event)) collectHere(client);
                }));
    }

    private static void pollPanicKey(Minecraft client) {
        boolean pressed = false;
        while (panicKey != null && panicKey.consumeClick()) pressed = true;
        if (pressed) togglePanic(client);
        boolean dump = false;
        while (dumpKey != null && dumpKey.consumeClick()) dump = true;
        if (dump) dumpHere(client);
        boolean collect = false;
        while (collectKey != null && collectKey.consumeClick()) collect = true;
        if (collect) collectHere(client);
    }

    /** Writes whatever screen is open right now to the dump file, GUI open or not. */
    private static void dumpHere(Minecraft client) {
        MarketFeed.dumpOpenScreen(client, msg -> {
            if (client.player != null) client.player.sendSystemMessage(msg);
        });
    }

    /** Resumes collection from whatever order screen is already in front of you. */
    private static void collectHere(Minecraft client) {
        String result = OrderFlow.collectFrom(client, itemId);
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("[EchestMM] " + result));
        }
    }

    private static void togglePanic(Minecraft client) {
        if (state == State.OFF || state == State.PAUSED) {
            start(msg -> {
                if (client.player != null) client.player.sendSystemMessage(msg);
            });
        } else {
            panicStop(client);
        }
    }

    /** Immediate hard stop: no further packets, queued clicks dropped, any screen closed. */
    private static void panicStop(Minecraft client) {
        setState(State.OFF, "panic key");
        queue.clear();
        containerStuckSinceMs = 0L;
        containerCloseAttempts = 0;
        if (client.player != null) {
            Screen screen = client.gui.screen();
            if (screen != null) closeScreen(client.player, screen);
            client.player.sendSystemMessage(Component.literal(
                    "\u00a7c[EchestMM] PANIC STOP. Listed " + listed + ", sold " + sold + " for $"
                            + grossSold + ", bought " + bought + " for $" + grossBought + "."));
        }
        MarketFeed.log("engine", "panic stop");
    }

    private EchestEngine() {}

    static void init() {
        registerPanicKey();
        ClientTickEvents.END_CLIENT_TICK.register(EchestEngine::tick);
        // These two run even when the engine is off: the panic key has to be able to switch it on,
        // and a disconnect has to be classified after the fact.
        History.loadCutoff();
        ClientTickEvents.END_CLIENT_TICK.register(EchestEngine::pollPanicKey);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (OrderFlow.busy() && client.player != null) {
                OrderFlow.tick(client, System.currentTimeMillis());
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(EchestEngine::checkDisconnectCause);
        MarketFeed.onReceipt(EchestEngine::onReceipt);
        MarketFeed.onMessage(EchestEngine::onMessage);
        // A kick arrives with no warning line, so the disconnect itself has to be the signal.
        // But quitting the game is also a disconnect: penalising that dropped the ceiling to
        // 0.55/s for no reason. The cause is therefore classified on the following ticks - an
        // involuntary disconnect leaves a DisconnectedScreen up, a deliberate quit does not.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            boolean wasTrading = state != State.OFF && state != State.PAUSED;
            state = State.OFF;
            queue.clear();
            containerStuckSinceMs = 0L;
            containerCloseAttempts = 0;
            if (wasTrading) disconnectCheckUntilTick = ticks + DISCONNECT_CLASSIFY_TICKS;
        });
        registerCommands();
    }

    /**
     * Classifies a recent disconnect. A kick or a lost connection leaves Minecraft showing a
     * {@link DisconnectedScreen}; quitting to the title screen or closing the game does not.
     * Only the involuntary case ratchets the pacing ceiling down.
     */
    private static void checkDisconnectCause(Minecraft client) {
        if (disconnectCheckUntilTick <= 0) return;
        Screen screen = client.gui.screen();
        if (screen instanceof DisconnectedScreen) {
            disconnectCheckUntilTick = 0;
            ActionPacer.penalize(System.currentTimeMillis(), "kicked or dropped while trading");
            return;
        }
        if (ticks >= disconnectCheckUntilTick) {
            disconnectCheckUntilTick = 0;
            MarketFeed.log("pacer", "disconnect looked deliberate (no disconnect screen); ceiling unchanged");
        }
    }

    /** The item's display name as the order dialogs spell it: "minecraft:obsidian" -> "Obsidian". */
    private static String deskItemName() {
        String path = itemId.substring(itemId.indexOf(':') + 1);
        StringBuilder b = new StringBuilder(path.length());
        for (String word : path.split("_")) {
            if (word.isEmpty()) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return b.toString();
    }

    // ---- commands --------------------------------------------------------------------------
    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("echestmm")
                        .executes(ctx -> {
                            if (state == State.OFF || state == State.PAUSED) start(ctx.getSource()::sendFeedback);
                            else stop(ctx.getSource()::sendFeedback, "stopped by command");
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(literal("status").executes(ctx -> feedback(ctx.getSource()::sendFeedback, status())))
                        .then(literal("pace").executes(ctx -> feedback(ctx.getSource()::sendFeedback, pacing())))
                        .then(literal("pacereset").executes(ctx -> {
                            ActionPacer.reset(System.currentTimeMillis(), "manual reset");
                            return feedback(ctx.getSource()::sendFeedback, pacing());
                        }))
                        .then(literal("calibrate").executes(ctx ->
                                feedback(ctx.getSource()::sendFeedback, calibrate())))
                        .then(literal("debug").executes(ctx -> {
                            debug = !debug;
                            return feedback(ctx.getSource()::sendFeedback, "trace " + (debug ? "ON" : "OFF"));
                        }))
                        .then(literal("desk").then(argument("name", StringArgumentType.word()).executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
                            return feedback(ctx.getSource()::sendFeedback, applyDesk(name));
                        })))
                        .then(literal("item").then(argument("item", StringArgumentType.word()).executes(ctx -> {
                            String raw = StringArgumentType.getString(ctx, "item").toLowerCase(Locale.ROOT);
                            itemId = raw.contains(":") ? raw : "minecraft:" + raw;
                            searchTerm = itemId.substring(itemId.indexOf(':') + 1).replace('_', ' ');
                            invalidateCaches();
                            return feedback(ctx.getSource()::sendFeedback, "item " + itemId + ", search \"" + searchTerm + "\"");
                        })))
                        .then(literal("unit").then(argument("size", IntegerArgumentType.integer(1, 64)).executes(ctx -> {
                            unitSize = IntegerArgumentType.getInteger(ctx, "size");
                            return feedback(ctx.getSource()::sendFeedback, "listing unit " + unitSize + " item(s)");
                        })))
                        .then(literal("sell").then(argument("on", BoolArgumentType.bool()).executes(ctx -> {
                            selling = BoolArgumentType.getBool(ctx, "on");
                            return feedback(ctx.getSource()::sendFeedback, "sell side " + (selling ? "ON" : "OFF"));
                        })))
                        .then(literal("squeeze").then(argument("on", BoolArgumentType.bool()).executes(ctx -> {
                            squeezing = BoolArgumentType.getBool(ctx, "on");
                            squeeze = null;
                            squeezeUntilMs = 0L;
                            return feedback(ctx.getSource()::sendFeedback, "squeeze " + (squeezing ? "ON" : "OFF"));
                        })))
                        .then(literal("acquire").then(argument("on", BoolArgumentType.bool()).executes(ctx -> {
                            acquiring = BoolArgumentType.getBool(ctx, "on");
                            currentBid = 0;
                            return feedback(ctx.getSource()::sendFeedback, "bid ladder " + (acquiring ? "ON" : "OFF"));
                        })))
                        .then(literal("order")
                                .then(argument("amount", IntegerArgumentType.integer(1))
                                        .then(argument("priceperitem", IntegerArgumentType.integer(1))
                                                .executes(ctx -> feedback(ctx.getSource()::sendFeedback,
                                                        OrderFlow.place(itemId, deskItemName(),
                                                                IntegerArgumentType.getInteger(ctx, "amount"),
                                                                IntegerArgumentType.getInteger(ctx, "priceperitem")))))))
                        .then(literal("collect").executes(ctx ->
                                feedback(ctx.getSource()::sendFeedback, OrderFlow.collect(itemId))))
                        .then(literal("orderstatus").executes(ctx ->
                                feedback(ctx.getSource()::sendFeedback, OrderFlow.step() + ": " + OrderFlow.note())))
                        .then(literal("ordering").then(argument("on", BoolArgumentType.bool()).executes(ctx -> {
                            ordering = BoolArgumentType.getBool(ctx, "on");
                            return feedback(ctx.getSource()::sendFeedback,
                                    "acquire through buy orders " + (ordering ? "ON" : "OFF"));
                        })))
                        .then(literal("orderprofit").then(argument("dollars", IntegerArgumentType.integer(0))
                                .executes(ctx -> {
                                    minOrderProfit = IntegerArgumentType.getInteger(ctx, "dollars");
                                    return feedback(ctx.getSource()::sendFeedback,
                                            "an order sweep must model $" + minOrderProfit + " profit");
                                })))
                        .then(literal("sweep").executes(ctx ->
                                feedback(ctx.getSource()::sendFeedback, lastSweep.reason())))
                        .then(literal("book").executes(ctx -> {
                            for (String line : bookLadder()) {
                                ctx.getSource().sendFeedback(Component.literal(line));
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(literal("maxorder").then(argument("dollars", IntegerArgumentType.integer(0))
                                .executes(ctx -> {
                                    maxOrderCost = IntegerArgumentType.getInteger(ctx, "dollars");
                                    return feedback(ctx.getSource()::sendFeedback,
                                            "one order may commit at most $" + maxOrderCost);
                                })))
                        .then(literal("forget").executes(ctx -> {
                            String dropped = History.archive(itemId);
                            invalidateCaches();
                            lastCalibration = null;
                            return feedback(ctx.getSource()::sendFeedback, dropped);
                        }))
                        .then(literal("bal").executes(ctx -> {
                            // Manual override: the automatic timeout is 15s, and there is no
                            // reason to make anyone wait it out if cash is already suspected
                            // wrong. This forces exactly the same request path CHECK_OWN uses.
                            balanceRequested = false;
                            lastBalanceTick = -1_000_000L;
                            return feedback(ctx.getSource()::sendFeedback,
                                    "forcing a fresh /bal; current cash=" + (cash < 0 ? "unknown" : "$" + cash)
                                    + ". Watch echest.log for \"sent /bal\" and the \"[chat]\" line right "
                                    + "after it - that pair is the actual reply text.");
                        }))
                        .then(literal("snipe").then(argument("on", BoolArgumentType.bool()).executes(ctx -> {
                            sniping = BoolArgumentType.getBool(ctx, "on");
                            return feedback(ctx.getSource()::sendFeedback, "sniping " + (sniping ? "ON" : "OFF"));
                        })))
                        .then(literal("lift").then(argument("on", BoolArgumentType.bool()).executes(ctx -> {
                            lifting = BoolArgumentType.getBool(ctx, "on");
                            return feedback(ctx.getSource()::sendFeedback, "floor lifting " + (lifting ? "ON" : "OFF"));
                        })))
                        .then(literal("bid").then(argument("start", IntegerArgumentType.integer(1)).executes(ctx -> {
                            bidStart = IntegerArgumentType.getInteger(ctx, "start");
                            currentBid = 0;
                            return feedback(ctx.getSource()::sendFeedback, "ladder starts at " + bidStart);
                        })))
                        .then(literal("bidstep").then(argument("step", IntegerArgumentType.integer(1)).executes(ctx -> {
                            bidStep = IntegerArgumentType.getInteger(ctx, "step");
                            return feedback(ctx.getSource()::sendFeedback, "ladder step " + bidStep);
                        })))
                        .then(literal("spread").then(argument("percent", IntegerArgumentType.integer(1, 90)).executes(ctx -> {
                            minSpreadPct = IntegerArgumentType.getInteger(ctx, "percent") / 100.0;
                            return feedback(ctx.getSource()::sendFeedback, "minimum bid-ask spread "
                                    + Math.round(minSpreadPct * 100) + "%");
                        })))
                        .then(literal("probe").then(argument("seconds", IntegerArgumentType.integer(2)).executes(ctx -> {
                            probeSeconds = IntegerArgumentType.getInteger(ctx, "seconds");
                            return feedback(ctx.getSource()::sendFeedback, "ladder probe " + probeSeconds + "s");
                        })))
                        .then(literal("hold").then(argument("units", IntegerArgumentType.integer(0)).executes(ctx -> {
                            maxHoldUnits = IntegerArgumentType.getInteger(ctx, "units");
                            return feedback(ctx.getSource()::sendFeedback, "stop acquiring above " + maxHoldUnits + " units");
                        })))
                        .then(literal("min").then(argument("price", IntegerArgumentType.integer(1)).executes(ctx -> {
                            cfg = withMin(cfg, IntegerArgumentType.getInteger(ctx, "price"));
                            return feedback(ctx.getSource()::sendFeedback, "minimum price " + cfg.minPrice());
                        })))
                        .then(literal("open").then(argument("price", IntegerArgumentType.integer(1)).executes(ctx -> {
                            cfg = withFallback(cfg, IntegerArgumentType.getInteger(ctx, "price"));
                            return feedback(ctx.getSource()::sendFeedback, "empty-book price " + cfg.fallbackPrice());
                        })))
                        .then(literal("tick").then(argument("size", IntegerArgumentType.integer(1)).executes(ctx -> {
                            cfg = withTick(cfg, IntegerArgumentType.getInteger(ctx, "size"));
                            return feedback(ctx.getSource()::sendFeedback, "price grid " + cfg.tick());
                        })))
                        .then(literal("wait").then(argument("seconds", IntegerArgumentType.integer(0)).executes(ctx -> {
                            cfg = withHoldSeconds(cfg, IntegerArgumentType.getInteger(ctx, "seconds"));
                            return feedback(ctx.getSource()::sendFeedback, cfg.maxHoldSeconds() <= 0
                                    ? "fill-time budget OFF" : "fill-time budget " + Math.round(cfg.maxHoldSeconds()) + "s");
                        })))
                        .then(literal("margin").then(argument("percent", IntegerArgumentType.integer(1, 90)).executes(ctx -> {
                            cfg = withSnipeMargin(cfg, IntegerArgumentType.getInteger(ctx, "percent") / 100.0);
                            return feedback(ctx.getSource()::sendFeedback, "snipe margin "
                                    + Math.round(cfg.snipeMarginPct() * 100) + "%");
                        })))
                        .then(literal("recheck").executes(ctx -> {
                            invalidateCaches();
                            return feedback(ctx.getSource()::sendFeedback, "will re-read your listings and the market");
                        }))
                )
        );
    }

    /**
     * Selects a desk. Only the market's structure is fixed here - which item, how many go in one
     * listing, and which sides make sense. Every price level comes from {@link Desk#derive}, which
     * reads this account's realised trades out of the log.
     */
    private static String applyDesk(String name) {
        switch (name) {
            case "echest", "enderchest", "ender_chest" -> {
                itemId = "minecraft:ender_chest";
                searchTerm = "ender chest";
                unitSize = 1;
                selling = true;
                sniping = true;
                lifting = true;
                acquiring = false;   // chests are crafted, not bought
                squeezing = false;   // one at a time; there is nothing to squeeze with
            }
            case "obsidian", "obby" -> {
                itemId = "minecraft:obsidian";
                searchTerm = "obsidian";
                unitSize = 64;
                selling = true;
                // Three acquisition channels, cheapest-per-action first. Orders rest at our price
                // and get delivered to us; quickbuy takes the cheap tail the moment it appears;
                // the squeeze posts above the book before sweeping beneath it. Turning all of
                // them off is what left this desk watching a 350-vs-1250 spread do nothing.
                sniping = true;      // quickbuy the cheap tail off the open page
                lifting = false;     // superseded by the squeeze, which posts before it sweeps
                acquiring = false;   // the bid ladder is the order's job now
                squeezing = true;
            }
            default -> {
                return "unknown desk \"" + name + "\"; use echest or obsidian";
            }
        }
        resetDeskState();
        invalidateCaches();
        return "desk " + name + ": " + itemId + " x" + unitSize + " per listing. " + calibrate();
    }

    /**
     * Re-derives every price level for the current desk from realised trades. Called on a desk
     * switch and by {@code /echestmm calibrate}; safe to run mid-session.
     */
    private static String calibrate() {
        // Always from clean defaults, never from whatever the last desk derived. Carrying the
        // ender chest configuration into the obsidian desk imported a listing minimum an order of
        // magnitude too high, which filtered out every sane undercut candidate and left only the
        // absurd end of the book to choose from.
        Liquidity.Config base = Liquidity.Config.defaults();
        History.Samples samples = History.load(itemId, unitSize, 500);
        Desk.Calibration cal = Desk.derive(samples, cash, base);
        cfg = cal.cfg();
        lastCalibration = cal;
        if (cal.ladderReady()) {
            bidStart = cal.bidStart();
            bidCeiling = cal.bidCeiling();
            bidStep = cal.bidStep();
            minSpreadPct = cal.minSpreadPct();
            if (cal.maxHoldUnits() > 0) maxHoldUnits = cal.maxHoldUnits();
            currentBid = 0;
        } else if (acquiring) {
            acquiring = false;   // never bid on invented numbers
        }
        MarketFeed.log("desk", itemId + " x" + unitSize + ": " + cal.evidence());
        return cal.evidence();
    }

    private static Liquidity.Config withMin(Liquidity.Config c, long v) {
        return new Liquidity.Config(v, c.fallbackPrice(), c.tick(), c.maxHoldSeconds(), c.ladderStep(),
                c.snipeMarginPct(), c.liftMarginPct(), c.maxLiftUnits(), c.maxCashSharePct());
    }

    private static Liquidity.Config withFallback(Liquidity.Config c, long v) {
        return new Liquidity.Config(c.minPrice(), v, c.tick(), c.maxHoldSeconds(), c.ladderStep(),
                c.snipeMarginPct(), c.liftMarginPct(), c.maxLiftUnits(), c.maxCashSharePct());
    }

    private static Liquidity.Config withTick(Liquidity.Config c, long v) {
        return new Liquidity.Config(c.minPrice(), c.fallbackPrice(), v, c.maxHoldSeconds(), c.ladderStep(),
                c.snipeMarginPct(), c.liftMarginPct(), c.maxLiftUnits(), c.maxCashSharePct());
    }

    private static Liquidity.Config withHoldSeconds(Liquidity.Config c, double v) {
        return new Liquidity.Config(c.minPrice(), c.fallbackPrice(), c.tick(), v, c.ladderStep(),
                c.snipeMarginPct(), c.liftMarginPct(), c.maxLiftUnits(), c.maxCashSharePct());
    }

    private static Liquidity.Config withSnipeMargin(Liquidity.Config c, double v) {
        return new Liquidity.Config(c.minPrice(), c.fallbackPrice(), c.tick(), c.maxHoldSeconds(), c.ladderStep(),
                v, c.liftMarginPct(), c.maxLiftUnits(), c.maxCashSharePct());
    }

    private static int feedback(Consumer<Component> out, String msg) {
        out.accept(Component.literal("[EchestMM] " + msg));
        return Command.SINGLE_SUCCESS;
    }

    private static String status() {
        return "state=" + state + (state == State.PAUSED ? " (" + pauseReason + ")" : "")
                + " | " + itemId + " x" + unitSize
                + " | ask=" + (lastQuote == null ? "n/a" : lastQuote.unitPrice() + "/item")
                + " | regime=" + Liquidity.classify(competingLevels())
                + " | clear=" + (Double.isFinite(clearRate) ? String.format(Locale.ROOT, "%.3f/s", clearRate) : "unmeasured")
                + " (" + fills.size() + " fills)"
                + " | free=" + localFree
                + " | listed=" + listed + " sold=" + sold + " ($" + grossSold + ")"
                + " | bought=" + bought + " ($" + grossBought + ")"
                + (acquiring ? " | bid=" + currentBid + " acquired=" + acquired + " ($" + acquiredCost + ")" : "")
                + (squeezing ? " | squeeze=" + (squeeze != null && squeeze.go()
                        ? squeeze.askUnitPrice() + "/item sweeping <=" + squeeze.sweepCeiling() + "/item"
                        : "waiting: " + (squeeze == null ? "not planned yet" : squeeze.reason())) : "")
                + " | cash=" + (cash < 0 ? "unknown" : "$" + cash)
                + " | pace=" + String.format(Locale.ROOT, "%.2f/s", ActionPacer.cap())
                + " | levels from " + (lastCalibration == null ? "defaults (not calibrated)"
                        : lastCalibration.samples() + " realised trades"
                          + (lastCalibration.ladderReady() ? "" : " - too few, ladder off"));
    }

    private static String pacing() {
        return "pacing ceiling " + String.format(Locale.ROOT, "%.2f", ActionPacer.cap())
                + " actions/s, " + ActionPacer.penalties() + " penalties on record, "
                + ActionPacer.cleanSeconds(System.currentTimeMillis()) + "s clean, "
                + ActionPacer.spent() + " actions this session."
                + " Kicks recorded at 2.0-2.2/s, so the ceiling only recovers after 10 clean minutes.";
    }

    // ---- lifecycle -------------------------------------------------------------------------

    private static void start(Consumer<Component> feedback) {
        invalidateCaches();
        retries = 0;
        waited = 0;
        cooldown = 0;
        cursorFixes = 0;
        queue.clear();
        currentBid = 0;
        lastProbeMs = System.currentTimeMillis();
        LocalPlayer p = Minecraft.getInstance().player;
        selfName = p == null ? "" : p.getGameProfile().name();
        ActionPacer.noteResumed(System.currentTimeMillis());
        setState(State.CHECK_OWN, "start");
        feedback.accept(Component.literal("[EchestMM] ON: " + itemId + " x" + unitSize + " per listing"
                + (selling ? ", selling at the liquidity price" : ", sell side off")
                + (acquiring ? ", bidding from " + bidStart + " under a " + Math.round(minSpreadPct * 100) + "% spread" : "")
                + ", pace " + String.format(Locale.ROOT, "%.2f", ActionPacer.cap()) + " actions/s."));
    }

    private static void stop(Consumer<Component> feedback, String why) {
        setState(State.OFF, why);
        queue.clear();
        feedback.accept(Component.literal("[EchestMM] OFF (" + why + "). Listed " + listed + ", sold " + sold
                + " for $" + grossSold + ", bought " + bought + " for $" + grossBought + "."));
    }

    private static void pause(LocalPlayer player, String why) {
        setState(State.PAUSED, why);
        pauseReason = why;
        queue.clear();
        invalidateCaches();
        player.sendSystemMessage(Component.literal("[EchestMM] PAUSED: " + why + "  (/echestmm to resume)"));
    }

    private static void invalidateCaches() {
        localFree = -1;
        lastMarketTick = -1;
        ownDirty = false;
        salesSinceOwnRead = 0;
    }

    /**
     * Forgets everything measured about the previous desk.
     *
     * <p>Two different items do not share a clearing rate, a basis, or a book. Without this the
     * obsidian desk borrowed the ender chest desk's 5.192 units/s, decided a 22-deep queue would
     * clear in four seconds, and listed a stack of obsidian at $473,600 - a price only the
     * aspirational top half of that book agreed with. Measurements belong to the desk that made
     * them.
     */
    private static void resetDeskState() {
        fills.clear();
        clearRate = Double.NaN;
        ownPriceCounts.clear();
        liveListings.clear();
        marketUnits.clear();
        marketAnyLot.clear();
        marketPagesRead = 0;
        lastComparableLots = 0;
        lastAnyLots = 0;
        listed = 0;
        sold = 0;
        grossSold = 0;
        bought = 0;
        grossBought = 0;
        acquired = 0;
        acquiredCost = 0;
        orderedItems = 0;
        orderedCost = 0;
        discoveredAsk = 0;
        currentBid = 0;
        squeeze = null;
        squeezeUntilMs = 0L;
        lastQuote = null;
        lastCalibration = null;
        lastSweep = Sweep.Plan.no("not evaluated");
        lastOrderMs = 0L;
        lastFillAtMs = 0L;
        lastProbeMs = 0L;
        lastProbeFilled = false;
    }

    private static void setState(State next, String why) {
        if (state == next) return;
        trace(state + " -> " + next + (why == null || why.isEmpty() ? "" : "  (" + why + ")"));
        state = next;
        waited = 0;
        queue.clear();
    }

    private static void trace(String msg) {
        MarketFeed.log("engine", msg);
        if (!debug) return;

        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) p.sendSystemMessage(Component.literal("\u00a77[EchestMM] " + msg));
    }

    // ---- server feedback -------------------------------------------------------------------

    private static void onReceipt(MarketFeed.Receipt r) {
        if (state == State.OFF) return;
        switch (r.kind()) {
            case "LIST" -> {
                if (state != State.WAIT_CONFIRM && state != State.WAIT_SOLD) return;
                if (r.count() != unitSize && r.count() != 0) {
                    LocalPlayer p = Minecraft.getInstance().player;
                    if (p != null) {
                        pause(p, "server listed " + r.count() + " items but the unit is " + unitSize
                                + ": \"" + r.raw() + "\"");
                    }
                    return;
                }
                listReceiptSeen = true;
            }
            case "SELL" -> onOwnSale(r);
            case "BUY" -> onOwnPurchase(r);
            default -> { }
        }
    }

    private static void onMessage(MarketFeed.MarketMessage m) {
        // The order flow runs independently of the engine's own on/off state.
        OrderFlow.noteChat(m.raw());
        if (state == State.OFF) return;
        switch (m.kind()) {
            case "RATE_LIMIT" -> ActionPacer.penalize(System.currentTimeMillis(), "server rate-limit line");
            case "ORDER_DELIVERY" -> {
                // Deliveries into a resting order are real acquisitions at the order's own price,
                // so they are the basis the sell side must clear. Without this the cost floor
                // would think everything was free.
                int items = MarketFeed.deliveredCount(m.raw());
                if (items > 0 && OrderFlow.lastOrderUnitPrice() > 0) {
                    orderedItems += items;
                    orderedCost += items * OrderFlow.lastOrderUnitPrice();
                }
            }
            case "AH_FULL" -> {
                if (state == State.SELL_CMD || state == State.WAIT_CONFIRM || state == State.WAIT_SOLD) {
                    localFree = 0;
                    ownDirty = true;
                    retries = 0;
                    closeAnyScreen();
                    enterFull("server: too many listed items");
                }
            }
            case "ALREADY_BOUGHT" -> {
                if (state == State.BUY_CONFIRM || state == State.BUY_SETTLE || state == State.BUY_WAIT_PAGE) {
                    trace("lost the race for slot " + buySlot + "; the page will refresh itself");
                    next("cheap listing was already bought");
                }
            }
            case "NO_FUNDS" -> {
                cash = 0;
                lastBalanceTick = -1_000_000L;
                trace("server reports insufficient funds; buying pauses until /bal refreshes");
                closeAnyScreen();
                next("insufficient funds");
            }
            case "BALANCE" -> {
                Matcher matcher = BALANCE.matcher(m.raw());
                if (matcher.find()) {
                    cash = Math.round(MarketFeed.compactNumber(matcher.group(1), matcher.group(2)));
                    lastBalanceTick = ticks;
                    balanceRequested = false;
                    trace("balance $" + cash);
                    // The hold cap is a function of cash; derive it now that cash is known.
                    if (acquiring && lastCalibration != null && lastCalibration.maxHoldUnits() == 0) {
                        calibrate();
                    }
                }
            }
            default -> { }
        }
    }

    private static void onOwnSale(MarketFeed.Receipt r) {
        if (!namesDeskItem(r.itemName())) return;
        if (localFree >= 0) localFree++;
        long total = Math.round(r.totalPrice());
        // Sale lines often omit the quantity, so an unspecified count means one of our listings.
        int units = r.count() > 0 ? r.count() : unitSize;
        long unit = unitPriceOf(r.totalPrice(), units);
        sold++;
        grossSold += total;
        Integer n = ownPriceCounts.get(unit);
        if (n != null && n > 0) {
            if (n == 1) ownPriceCounts.remove(unit);
            else ownPriceCounts.put(unit, n - 1);
        } else {
            ownDirty = true;
            trace("sale at " + unit + "/item was not in the local tally; will re-read your listings");
        }
        recordFill(unit, r.observedAtMs());
        if (state == State.WAIT_FULL) next("a sale freed a slot");
    }

    /** Turns a sale into a clearing-rate sample: queue depth at listing time over hold time. */
    private static void recordFill(long price, long soldAtMs) {
        LiveListing match = null;
        for (LiveListing l : liveListings) {
            if (l.price != price) continue;
            if (match == null || l.listedAtMs < match.listedAtMs) match = l;   // FIFO within a price
        }
        if (match == null) return;
        liveListings.remove(match);
        double holdSeconds = Math.max(0.001, (soldAtMs - match.listedAtMs) / 1000.0);
        fills.add(new Liquidity.Fill(match.queueAhead, holdSeconds));
        if (fills.size() > 200) fills.removeFirst();
        clearRate = Liquidity.clearRate(fills);
        lastFillAtMs = soldAtMs > 0 ? soldAtMs : System.currentTimeMillis();
        trace("filled " + price + " after " + Math.round(holdSeconds) + "s from queue " + match.queueAhead
                + "; clearing rate " + String.format(Locale.ROOT, "%.3f", clearRate) + " units/s");
    }

    private static void onOwnPurchase(MarketFeed.Receipt r) {
        if (!namesDeskItem(r.itemName())) return;
        long price = Math.round(r.totalPrice());
        bought++;
        grossBought += price;
        if (cash >= 0) cash = Math.max(0, cash - price);
        buyBudgetRemaining = Math.max(0, buyBudgetRemaining - price);
        lastMarketTick = -1;
        if (acquiring) {
            acquired++;
            acquiredCost += price;
            lastAcquireFillMs = System.currentTimeMillis();
            lastProbeFilled = true;
        }
        trace("bought " + r.count() + "x at " + price + " (" + buyReason + ")");
        if (state == State.BUY_CONFIRM || state == State.BUY_SETTLE) next("bought");
    }

    /** "Ender Chest" names minecraft:ender_chest. */
    private static boolean namesDeskItem(String itemName) {
        if (itemName == null) return false;
        String path = itemId.substring(itemId.indexOf(':') + 1);
        return itemName.trim().toLowerCase(Locale.ROOT).replace(' ', '_').equals(path);
    }

    private static void closeAnyScreen() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        Screen screen = client.gui.screen();
        if (screen != null) closeScreen(client.player, screen);
    }

    // ---- dispatch --------------------------------------------------------------------------

    private static boolean needOwnRead() {
        return localFree < 0 || ownDirty || salesSinceOwnRead >= OWN_REREAD_EVERY;
    }

    private static boolean needMarketRead() {
        return lastMarketTick < 0 || ticks - lastMarketTick >= MARKET_RECHECK_TICKS;
    }

    private static boolean needBalance() {
        // ordering was missing here: a desk configured to acquire only through orders (obsidian,
        // as of the last few builds) could reach every gate below with cash genuinely known to be
        // needed and still never ask for it, because none of its own flags were in this list.
        return (sniping || lifting || acquiring || squeezing || ordering)
                && (cash < 0 || ticks - lastBalanceTick >= BALANCE_REFRESH_TICKS);
    }

    /**
     * Re-plans the squeeze from the current book, inventory, slots, cash and clearing rate.
     * Cheap - it is pure arithmetic over the cached book - so it runs on every dispatch.
     */
    private static void planSqueeze(long nowMs) {
        if (!squeezing) { squeeze = null; return; }
        if (squeeze != null && squeeze.go() && nowMs < squeezeUntilMs) return;
        if (nowMs - lastSqueezePlanMs < 1_000L) return;
        lastSqueezePlanMs = nowMs;
        int held = countInventory(Minecraft.getInstance());
        long costPer = acquired > 0 ? acquiredCost / Math.max(1, acquired * Math.max(1, unitSize))
                : cfg.minPrice();
        Campaign.Plan plan = Campaign.plan(competingLevels(), held, costPer, Math.max(0, localFree),
                unitSize, cash, clearRate, sanityMaxAsk(), cfg);
        if (plan.go() && (squeeze == null || !squeeze.go() || plan.askUnitPrice() != squeeze.askUnitPrice())) {
            trace("squeeze: " + plan.reason());
        } else if (!plan.go() && squeeze != null && squeeze.go()) {
            trace("squeeze off: " + plan.reason());
        }
        squeeze = plan;
        squeezeUntilMs = plan.go() ? nowMs + SQUEEZE_REPLAN_MS : 0L;
    }

    /**
     * The single dispatcher. Under a squeeze the order is deliberate: post the asks first, then
     * clear everything under them. Sweeping first would just hand cheap supply to whoever is
     * still undercutting us.
     */
    private static void next(String why) {
        long nowMs = System.currentTimeMillis();
        if (needOwnRead()) { setState(State.CHECK_OWN, why + "; need your-listings read"); return; }
        if (needMarketRead()) { setState(State.CHECK_MARKET, why + "; need market read"); return; }

        planSqueeze(nowMs);
        if (squeeze != null && squeeze.go()) {
            boolean postedEnough = ownTotal() > 0 && ownPriceCounts.containsKey(squeeze.askUnitPrice());
            boolean stockLeft = countInventory(Minecraft.getInstance()) >= unitSize;
            if (selling && stockLeft && localFree > 0 && !postedEnough) {
                setState(State.PREPARE_SELL, why + "; posting squeeze asks at " + squeeze.askUnitPrice());
                return;
            }
            if (planBuy()) { setState(State.BUY_OPEN, why + "; " + buyReason); return; }
            if (selling && stockLeft && localFree > 0) {
                setState(State.PREPARE_SELL, why + "; topping up squeeze asks");
                return;
            }
        } else if (planBuy()) {
            setState(State.BUY_OPEN, why + "; " + buyReason);
            return;
        }

        if (!selling) setState(State.WAIT_FULL, why + "; sell side off");
        else if (localFree == 0) enterFull(why);
        else setState(State.PREPARE_SELL, why);
    }

    private static void enterFull(String why) {
        if (state == State.WAIT_FULL) return;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null && selling) {
            p.sendSystemMessage(Component.literal("[EchestMM] all listing slots are taken; waiting for a sale"));
        }
        setState(State.WAIT_FULL, why);
    }

    // ---- tick ------------------------------------------------------------------------------

    private static void tick(Minecraft client) {
        ticks++;
        LocalPlayer player = client.player;
        if (state == State.OFF || state == State.PAUSED || player == null || client.gameMode == null
                || client.getConnection() == null) return;


        // A running order flow owns the GUI: it is a guarded, recorded sequence and the trading
        // state machine must not click underneath it. It is ticked separately so it also runs
        // while the engine itself is off.
        if (OrderFlow.busy()) return;
        if (cooldown > 0) { cooldown--; return; }
        InventoryMenu inv = player.inventoryMenu;
        Screen screen = client.gui.screen();
        long nowMs = System.currentTimeMillis();

        switch (state) {
            case CHECK_OWN -> {
                // Every send in this state machine is traced except this one, and cash has been
                // -1 (unknown) for this mod's entire logged history as a direct result: there is
                // no evidence in any session log that /bal was ever sent, or that a reply ever
                // arrived, because neither was ever written down. Every acquisition channel gates
                // on cash > 0, so an unlogged, possibly-never-answered /bal silently disables the
                // whole buy side with nothing in the log pointing at why.
                if (needBalance() && !balanceRequested && readyForCommand(player, inv, screen)) {
                    if (!sendCommand(client, "bal")) return;
                    balanceRequested = true;
                    balanceRequestedAtMs = nowMs;
                    trace("sent /bal (cash was " + (cash < 0 ? "unknown" : "$" + cash) + ")");
                    return;
                }
                // A request that never gets answered - wrong reply format, rate limit, a dropped
                // packet - used to leave balanceRequested stuck true forever, since only a parsed
                // BALANCE message ever clears it. That permanently blocked every future /bal for
                // the rest of the session. It now expires and logs the fact plainly.
                if (balanceRequested && nowMs - balanceRequestedAtMs > BALANCE_REPLY_TIMEOUT_MS) {
                    balanceRequested = false;
                    trace("no /bal reply understood within "
                            + (BALANCE_REPLY_TIMEOUT_MS / 1000) + "s; will retry. Check echest.log "
                            + "for \"[chat]\" or \"[unmatched]\" lines around the send to see the "
                            + "server's actual reply text.");
                }
                if (!readyForCommand(player, inv, screen)) return;
                if (!sendCommand(client, "ah")) return;
                requestMs = nowMs;
                setState(State.WAIT_OWN_AH, "sent /ah");
            }
            case WAIT_OWN_AH -> {
                if (screen instanceof AbstractContainerScreen<?> container
                        && MarketFeed.isAuctionPage(screen.getTitle().getString())) {
                    AbstractContainerMenu menu = container.getMenu();
                    int top = menu.getItems().size() - MarketFeed.PLAYER_INVENTORY_SLOTS;
                    if (top < 9) { pause(player, "unexpected auction layout (" + top + " top slots)"); return; }
                    int chestSlot = top - 9 + 6;   // last row, 7th slot: "Your Items"
                    if (!clickSlot(client, player, menu, chestSlot, 1.0)) return;
                    requestMs = nowMs;
                    setState(State.WAIT_OWN_SCAN, "clicked Your Items at slot " + chestSlot);
                } else if (++waited > SCREEN_TIMEOUT_TICKS) {
                    retryOrPause(player, screen, "auction screen did not open");
                }
            }
            case WAIT_OWN_SCAN -> {
                MarketFeed.OwnListings own = MarketFeed.latestOwnListings();
                if (own != null && own.observedAtMs() >= requestMs) {
                    applyOwnRead(own);
                    closeScreen(player, screen);
                    retries = 0;
                    next(localFree + " free, " + ownTotal() + " of ours live");
                } else if (++waited > SCREEN_TIMEOUT_TICKS) {
                    retryOrPause(player, screen, "Your Items screen did not open or could not be read");
                }
            }
            case CHECK_MARKET -> {
                marketPagesRead = 0;   // a new scan assembles a new book
                // An open search page is free market data. It has to be a *search* page, though:
                // the /ah main menu is also titled "Auction House | Page 1", and reusing it read
                // the book as empty, which silently disabled every price decision downstream.
                MarketFeed.PageScan open = MarketFeed.latestPage();
                if (isUsableBookPage(open, nowMs, screen)) {
                    if (readMarket(player, open)) { retries = 0; next("reused the open search page"); }
                    return;
                }
                if (!readyForCommand(player, inv, screen)) return;
                if (!sendCommand(client, "ah " + searchTerm)) return;
                requestMs = nowMs;
                setState(State.WAIT_MARKET, "sent /ah " + searchTerm);
            }
            case WAIT_MARKET -> {
                MarketFeed.PageScan page = MarketFeed.latestPage();
                if (page != null && page.observedAtMs() >= requestMs
                        && page.page() >= marketPagesRead + 1) {
                    boolean ok = readMarket(player, page);
                    marketPagesRead++;
                    // Donut sorts the search page by listing total, so a stack desk's real
                    // competition is never on page 1: it is a wall of 1x lots there. Keep paging
                    // until lots of our own size appear, or we run out of patience.
                    if (ok && lastComparableLots == 0 && unitSize > 1
                            && marketPagesRead < MAX_MARKET_PAGES
                            && screen instanceof AbstractContainerScreen<?> container) {
                        int nextSlot = findNextPageSlot(client, container);
                        if (nextSlot >= 0 && clickSlot(client, player, container.getMenu(), nextSlot, 1.0)) {
                            requestMs = nowMs;
                            waited = 0;
                            trace("page " + marketPagesRead + " had no " + unitSize
                                    + "x lots; paging deeper for comparable stacks");
                            return;
                        }
                    }
                    if (!planBuy()) closeScreen(player, screen);   // keep the page for the buy side
                    if (ok) { retries = 0; next("market read"); }
                } else if (++waited > SCREEN_TIMEOUT_TICKS) {
                    retryOrPause(player, screen, "market page did not load");
                }
            }
            case WAIT_FULL -> {
                // Out of stock is not out of work, and this state used to prove otherwise: it
                // counted ticks while the obsidian book sat at floor 1250/item against a 350/item
                // basis. "The buy side keeps working" was a claim nothing in here implemented.
                //
                // With no inventory the desk's whole job is acquisition, so the buy side is
                // consulted first - order sweep or quickbuy, whichever the planner picks.
                if (planBuy()) {
                    setState(State.BUY_OPEN, "out of stock; " + buyReason);
                    return;
                }
                // A stale book is why a sweep gets refused, so refresh it rather than wait out the
                // full recheck: the market read is what every buy decision is made from.
                if (waited > 0 && waited % MARKET_REFRESH_WHILE_EMPTY_TICKS == 0) {
                    setState(State.CHECK_MARKET, "out of stock; re-reading the book to price a sweep");
                    return;
                }
                if (++waited >= FULL_RECHECK_TICKS) {
                    localFree = -1;
                    setState(State.CHECK_OWN, "re-check after wait");
                }
            }
            case BUY_OPEN -> {
                MarketFeed.PageScan open = MarketFeed.latestPage();
                if (isUsableBookPage(open, nowMs, screen)) {
                    // The live page keeps updating from inbound packets: no command, no cooldown,
                    // and prices stay current between clicks.
                    requestMs = open.observedAtMs();
                    setState(State.BUY_WAIT_PAGE, "reusing the live page");
                    return;
                }
                if (!readyForCommand(player, inv, screen)) return;
                if (!sendCommand(client, "ah " + searchTerm)) return;
                requestMs = nowMs;
                setState(State.BUY_WAIT_PAGE, "sent /ah " + searchTerm + " to buy");
            }
            case BUY_WAIT_PAGE -> {
                MarketFeed.PageScan page = MarketFeed.latestPage();
                if (!(screen instanceof AbstractContainerScreen<?> container)
                        || page == null || page.observedAtMs() < requestMs || page.page() != 1) {
                    if (++waited > SCREEN_TIMEOUT_TICKS) retryOrPause(player, screen, "market page did not load to buy");
                    return;
                }
                if (!readMarket(player, page)) return;
                MarketFeed.Listing target = pickBuyTarget(page);
                if (target == null) {
                    lastProbeMs = nowMs;
                    lastProbeFilled = false;
                    closeScreen(player, screen);
                    next("nothing at or under " + buyMaxPay + " on page 1");
                    return;
                }
                buySlot = target.slot();
                if (!clickSlot(client, player, container.getMenu(), buySlot, 1.0)) return;
                requestMs = nowMs;
                setState(State.BUY_CONFIRM, "clicked " + Math.round(target.totalPrice())
                        + " (ceiling " + buyMaxPay + ")");
            }
            case BUY_CONFIRM -> {
                if (screen instanceof DialogScreen<?> dialog) {
                    MarketFeed.QuoteRead quote = MarketFeed.readDialogQuote(client, dialog);
                    if (!acceptBuyQuote(player, quote)) return;
                    AbstractButton button = bestPositiveButton(screen.children());
                    if (button == null) {
                        if (++waited > SCREEN_TIMEOUT_TICKS) {
                            retryOrPause(player, screen, "buy dialog has no Yes-like button: "
                                    + buttonLabels(screen.children()));
                        }
                        return;
                    }
                    if (!ActionPacer.charge(nowMs, 1.0)) return;
                    button.onPress(null);
                    requestMs = nowMs;
                    setState(State.BUY_SETTLE, "confirmed at or under " + buyMaxPay);
                    return;
                }
                if (screen instanceof AbstractContainerScreen<?> container
                        && !MarketFeed.isAuctionPage(screen.getTitle().getString())) {
                    MarketFeed.QuoteRead quote = MarketFeed.readContainerQuote(client, container);
                    if (!acceptBuyQuote(player, quote)) return;
                    int slot = confirmSlot(container.getMenu());
                    if (slot < 0) {
                        if (++waited > SCREEN_TIMEOUT_TICKS) retryOrPause(player, screen, "no confirm slot in the buy chest");
                        return;
                    }
                    if (!clickSlot(client, player, container.getMenu(), slot, 1.0)) return;
                    requestMs = nowMs;
                    setState(State.BUY_SETTLE, "confirmed at or under " + buyMaxPay);
                    return;
                }
                if (++waited > SCREEN_TIMEOUT_TICKS) {
                    retryOrPause(player, screen, "no buy confirmation surface appeared");
                }
            }
            case BUY_SETTLE -> {
                if (++waited > SOLD_TIMEOUT_TICKS) {
                    lastMarketTick = -1;
                    retries = 0;
                    next("buy confirmation timed out");
                }
            }
            case PREPARE_SELL -> {
                if (needOwnRead() || needMarketRead() || localFree == 0) {
                    next(localFree == 0 ? "no free slot" : "cache expired");
                    return;
                }
                if (screen != null) { closeScreen(player, screen); cooldown = 3; return; }
                if (player.containerMenu != inv) {
                    if (++waited % 40 == 0) trace("waiting: a server-side container is still open");
                    return;
                }
                if (!queue.isEmpty()) {
                    Click c = queue.peek();
                    if (!ActionPacer.charge(nowMs, CLICK_WEIGHT)) return;
                    queue.poll();
                    client.gameMode.handleContainerInput(inv.containerId, c.slot, c.button, c.input, player);
                    cooldown = CLICK_INTERVAL_TICKS;
                    return;
                }
                boolean settled = ticks - lastContainerCloseTick >= CLICK_SETTLE_TICKS;
                if (!inv.getCarried().isEmpty()) {
                    if (!settled) return;
                    if (!queuePutBack(player, inv, -1)) pause(player, "the cursor is holding an item");
                    return;
                }
                int wrongSlot = findWrongSizedHotbarStack(inv);
                if (wrongSlot >= 0) {
                    if (!settled) return;
                    if (!queuePutBack(player, inv, wrongSlot)) {
                        pause(player, "hotbar slot " + (wrongSlot - 36 + 1) + " holds "
                                + inv.getSlot(wrongSlot).getItem().getCount() + " but the listing unit is "
                                + unitSize + "; move it out of the hotbar");
                    }
                    return;
                }
                int slot = findHotbarUnit(inv);
                if (settled && slot < 0) {
                    if (queueRefillUnit(player, inv)) return;
                    return;
                }
                if (slot < 0) return;
                Liquidity.Quote quote = quoteNow();
                lastQuote = quote;
                if (quote.unitPrice() <= 0) {
                    // No comparable price exists yet. Not a fault and not a reason to pause the
                    // desk: the buy side still has work, and the next market read may page deeper.
                    trace("not listing: " + quote.reason());
                    cooldown = 60;
                    setState(State.WAIT_FULL, "no comparable price; buy side continues");
                    return;
                }
                if (quote.unitPrice() < cfg.minPrice()) {
                    pause(player, "computed price " + quote.unitPrice() + "/item is under the minimum "
                            + cfg.minPrice());
                    return;
                }
                // The quote is per item; the auction house wants the price of the whole listing.
                currentUnitPrice = quote.unitPrice();
                currentPrice = Liquidity.quantize(quote.unitPrice() * unitSize, cfg.tick());
                currentQueueAhead = quote.queueAhead();
                sellSlot = slot;
                int hotbarIndex = slot - 36;
                player.getInventory().setSelectedSlot(hotbarIndex);
                client.getConnection().send(new ServerboundSetCarriedItemPacket(hotbarIndex));
                setState(State.SELL_CMD, "hotbar " + (hotbarIndex + 1) + " at " + currentPrice
                        + " (" + quote.reason() + ")");
                cooldown = 2;
            }
            case SELL_CMD -> {
                ItemStack held = inv.getSlot(sellSlot).getItem();
                if (!isDeskItem(held) || held.getCount() != unitSize
                        || player.getInventory().getSelectedSlot() != sellSlot - 36) {
                    pause(player, "the held slot changed before listing (count=" + held.getCount()
                            + ", unit=" + unitSize + ")");
                    return;
                }
                // Passing the live screen matters: with null, an open GUI was never closed and the
                // engine waited on a container it was refusing to shut.
                if (!readyForCommand(player, inv, screen)) return;
                listReceiptSeen = false;
                if (!sendCommand(client, "ah sell " + currentPrice)) return;
                setState(State.WAIT_CONFIRM, "sent /ah sell " + currentPrice);
            }
            case WAIT_CONFIRM -> {
                if (!isDeskItem(inv.getSlot(sellSlot).getItem())) { onListed(player, screen); return; }
                if (screen != null && pressSellConfirm(client, player, screen)) {
                    setState(State.WAIT_SOLD, "pressed confirm");
                } else if (++waited > SCREEN_TIMEOUT_TICKS) {
                    retryOrPause(player, screen, "no confirmation screen after /ah sell");
                }
            }
            case WAIT_SOLD -> {
                if (!isDeskItem(inv.getSlot(sellSlot).getItem())) { onListed(player, screen); return; }
                if (++waited > SOLD_TIMEOUT_TICKS) {
                    retryOrPause(player, screen, "confirmed but the item is still in the hotbar");
                }
            }
            default -> { }
        }
    }

    /**
     * A listing's price per item. The auction house grid applies to the <em>listing total</em>, so
     * the per-item grid is that tick divided by the stack size: snapping per-item prices to the
     * full 100 turned a 29,000 stack sale (453/item) into 400/item, a 12% error that also stopped
     * sale receipts from matching our own listing tally.
     */
    private static long unitPriceOf(double totalPrice, int count) {
        int units = Math.max(1, count);
        long grid = Math.max(1, Math.max(1, cfg.tick()) / Math.max(1, unitSize));
        long perItem = Math.max(1L, Math.round(totalPrice / units));
        return Liquidity.quantize(perItem, grid);
    }

    /**
     * True when a command may be sent: no client screen open, and the server agrees no container
     * is open.
     *
     * <p>Those two can disagree. The measured failure was two stalls of 238 s and 258 s spent
     * logging "a server-side container is still open" while nothing broke the deadlock, so a close
     * packet is forced after {@code CONTAINER_STALL_MS} and the state machine gives up rather than
     * spinning if the server ignores several of them.
     */
    private static boolean readyForCommand(LocalPlayer player, InventoryMenu inv, Screen screen) {
        if (screen != null) { closeScreen(player, screen); cooldown = 3; return false; }
        if (player.containerMenu != inv) {
            long nowMs = System.currentTimeMillis();
            if (containerStuckSinceMs == 0L) containerStuckSinceMs = nowMs;
            long stalledMs = nowMs - containerStuckSinceMs;
            if (stalledMs >= CONTAINER_STALL_MS) {
                if (++containerCloseAttempts > MAX_CONTAINER_CLOSE_ATTEMPTS) {
                    containerStuckSinceMs = 0L;
                    containerCloseAttempts = 0;
                    retryOrPause(player, null, "server kept a container open for "
                            + stalledMs / 1000 + "s despite " + MAX_CONTAINER_CLOSE_ATTEMPTS + " close packets");
                    return false;
                }
                containerStuckSinceMs = nowMs;
                trace("container stuck for " + stalledMs / 1000 + "s; forcing a close packet ("
                        + containerCloseAttempts + "/" + MAX_CONTAINER_CLOSE_ATTEMPTS + ")");
                if (ActionPacer.charge(nowMs, 1.0)) player.closeContainer();
            }
            return false;
        }
        containerStuckSinceMs = 0L;
        containerCloseAttempts = 0;
        return true;
    }

    /** Charges the pacer and sends; false means the budget said not yet. */
    private static boolean sendCommand(Minecraft client, String command) {
        if (!ActionPacer.charge(System.currentTimeMillis(), 1.0)) return false;
        client.getConnection().sendCommand(command);
        return true;
    }

    private static boolean clickSlot(Minecraft client, LocalPlayer player, AbstractContainerMenu menu,
                                     int slot, double weight) {
        if (slot < 0) return false;
        if (!ActionPacer.charge(System.currentTimeMillis(), weight)) return false;
        client.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, player);
        return true;
    }

    private static void onListed(LocalPlayer player, Screen screen) {
        listed++;
        salesSinceOwnRead++;
        if (localFree > 0) localFree--;
        if (localFree == 0) localFree = -1;
        ownPriceCounts.merge(currentUnitPrice, 1, Integer::sum);
        liveListings.add(new LiveListing(currentUnitPrice, System.currentTimeMillis(), currentQueueAhead));
        if (liveListings.size() > 64) liveListings.removeFirst();
        if (screen != null) closeScreen(player, screen);
        retries = 0;
        cursorFixes = 0;
        next("listed at " + currentPrice + (listReceiptSeen ? " (receipt)" : " (item left the hotbar)"));
    }

    private static void closeScreen(LocalPlayer player, Screen screen) {
        player.closeContainer();
        if (screen instanceof AbstractContainerScreen<?>) lastContainerCloseTick = ticks;
    }

    private static void retryOrPause(LocalPlayer player, Screen screen, String why) {
        trace("problem: " + why + "; open screen = "
                + (screen == null ? "none" : screen.getClass().getSimpleName() + " \"" + screen.getTitle().getString() + "\""));
        if (screen != null) closeScreen(player, screen);
        if (++retries > MAX_RETRIES) { pause(player, why + " (" + MAX_RETRIES + " retries)"); return; }
        invalidateCaches();
        setState(State.CHECK_OWN, "retry " + retries);
    }

    // ---- market and pricing ----------------------------------------------------------------

    private static int ownTotal() {
        int n = 0;
        for (int v : ownPriceCounts.values()) n += v;
        return n;
    }

    private static void applyOwnRead(MarketFeed.OwnListings own) {
        localFree = own.free();
        salesSinceOwnRead = 0;
        ownDirty = false;
        ownPriceCounts.clear();
        for (MarketFeed.Listing l : own.listings()) {
            if (itemId.equals(l.itemId()) && Double.isFinite(l.totalPrice())) {
                ownPriceCounts.merge(unitPriceOf(l.totalPrice(), l.count()), 1, Integer::sum);
            }
        }
        liveListings.removeIf(live -> !ownPriceCounts.containsKey(live.price));
    }

    /**
     * True when a page scan really is our item's search results and is fresh enough to act on.
     *
     * <p>Three checks, each earned: page 1 and fresh, an open container screen, and - the one that
     * was missing - evidence that this is the search rather than the auction house lobby. The
     * lobby carries the same "Auction House | Page 1" title and holds no listings, so accepting it
     * made every book read return zero and every price fall back to the empty-book branch.
     */
    private static boolean isUsableBookPage(MarketFeed.PageScan page, long nowMs, Screen screen) {
        if (page == null || page.page() != 1) return false;
        if (nowMs - page.observedAtMs() > PAGE_FRESH_MS) return false;
        if (!(screen instanceof AbstractContainerScreen<?>)) return false;
        if (!MarketFeed.isAuctionPage(screen.getTitle().getString())) return false;
        String search = page.search() == null ? "" : page.search().toLowerCase(Locale.ROOT);
        String want = searchTerm.toLowerCase(Locale.ROOT);
        if (!search.isBlank() && (search.contains(want) || want.contains(search))) return true;
        // Some layouts do not echo the search into the title; a page holding our item proves it.
        for (MarketFeed.Listing l : page.listings()) {
            if (itemId.equals(l.itemId())) return true;
        }
        return false;
    }

    private static boolean readMarket(LocalPlayer player, MarketFeed.PageScan page) {
        List<MarketFeed.Listing> all = page.listings();
        if (all.size() >= 4) {
            int good = 0;
            for (int i = 1; i < all.size(); i++) {
                if (all.get(i).totalPrice() + 0.5 >= all.get(i - 1).totalPrice()) good++;
            }
            if (good < (all.size() - 1) * 0.9) {
                pause(player, "the search page is not sorted by lowest price; set the auction filter to Lowest Price");
                return false;
            }
        }
        // Page 1 starts a fresh book; deeper pages add to it, because the whole point of paging
        // is to assemble one book out of several screens.
        if (marketPagesRead == 0) {
            marketCounts.clear();
            marketUnits.clear();
            marketAnyLot.clear();
            lastComparableLots = 0;
            lastAnyLots = 0;
        }
        int matching = lastAnyLots;
        int comparable = lastComparableLots;
        for (MarketFeed.Listing l : all) {
            if (!itemId.equals(l.itemId()) || !(l.totalPrice() > 0)) continue;
            long unit = unitPriceOf(l.totalPrice(), l.count());
            if (unit <= 0) continue;
            matching++;
            // Every lot, at its per-item price: this is what acquisition compares, because a
            // cheap 17-item lot is cheap supply regardless of how we intend to resell it.
            marketAnyLot.merge(unit, Math.max(1, l.count()), Integer::sum);
            // The sell side may only compare lots of our own size.
            //
            // Donut's search page sorts by *listing total*, so page 1 of "obsidian" is a wall of
            // 1x and 8x lots. Those price at 2,000/item while real 64x stacks trade at 469/item -
            // a 4x error that made this desk list stacks at $121,600 while quickbuy sat at
            // $30,000. Small lots are not our competition; a buyer of a stack does not buy 64
            // single blocks.
            if (Math.max(1, l.count()) != Math.max(1, unitSize)) continue;
            marketCounts.merge(unit, 1, Integer::sum);
            marketUnits.merge(unit, Math.max(1, l.count()), Integer::sum);
            comparable++;
        }
        lastComparableLots = comparable;
        lastAnyLots = matching;
        lastMarketTick = ticks;
        trace("market: " + matching + " " + itemId + " listings, any stack size"
                + (marketCounts.isEmpty() ? "" : ", floor " + marketCounts.firstKey() + "/item"));
        return true;
    }

    /**
     * The "next page" control, found by name.
     *
     * <p>Located by its tooltip rather than a hardcoded index, the same way the order screens are
     * read: a guessed slot number was wrong twice already, and a name is evidence the server
     * actually sends.
     */
    private static int findNextPageSlot(Minecraft client, AbstractContainerScreen<?> screen) {
        AbstractContainerMenu menu = screen.getMenu();
        List<ItemStack> stacks = menu.getItems();
        int top = Math.max(0, stacks.size() - MarketFeed.PLAYER_INVENTORY_SLOTS);
        for (int i = 0; i < top; i++) {
            ItemStack stack = stacks.get(i);
            if (stack == null || stack.isEmpty()) continue;
            String name = stack.getDisplayName().getString().toLowerCase(Locale.ROOT);
            String tip = MarketFeed.joinLines(MarketFeed.tooltipLines(client, stack), " ")
                    .toLowerCase(Locale.ROOT);
            if (name.contains("next") || tip.contains("next page")) return i;
        }
        MarketFeed.dumpOpenScreen(client, msg -> {});
        trace("no next-page control found on the auction page; dumped it to screen_dumps.txt");
        return -1;
    }

    /** Every lot in the book by per-item price: the supply acquisition can actually buy. */
    private static List<Liquidity.Level> supplyLevels() {
        List<Liquidity.Level> levels = new ArrayList<>(marketAnyLot.size());
        for (var e : marketAnyLot.entrySet()) {
            int units = Math.max(1, e.getValue());
            int listings = Math.max(1, units / Math.max(1, unitSize));
            levels.add(new Liquidity.Level(e.getKey(), listings, units));
        }
        return levels;
    }

    private static List<Liquidity.Level> competingLevels() {
        List<Liquidity.Level> levels = new ArrayList<>(marketCounts.size());
        for (var e : marketCounts.entrySet()) {
            int mine = ownPriceCounts.getOrDefault(e.getKey(), 0);
            int others = e.getValue() - mine;
            if (others <= 0) continue;
            int units = marketUnits.getOrDefault(e.getKey(), others);
            levels.add(new Liquidity.Level(e.getKey(), others, Math.max(others, units)));
        }
        return levels;
    }

    /**
     * The unit price the next listing goes out at.
     *
     * <p>Three regimes, in priority order: an active squeeze posts at its chosen level; an empty
     * or wholly-owned book is a price-discovery problem and probes upward; otherwise the revenue
     * rate model prices against the visible competition.
     */
    private static Liquidity.Quote quoteNow() {
        List<Liquidity.Level> levels = competingLevels();
        if (squeeze != null && squeeze.go() && squeezeUntilMs > System.currentTimeMillis()) {
            return new Liquidity.Quote(squeeze.askUnitPrice(),
                    Liquidity.queueAhead(levels, new ArrayList<>(ownPriceCounts.keySet()),
                            squeeze.askUnitPrice()),
                    Double.NaN, Double.NaN, "squeeze: " + squeeze.reason());
        }
        if (levels.isEmpty() && lastAnyLots > 0 && unitSize > 1) {
            // The page is full of this item, just not in our lot size. That is not an empty book
            // and must not trigger upward discovery: Donut sorts by listing total, so the stacks
            // we compete with are pages deeper. Saying so beats inventing a price.
            return new Liquidity.Quote(0, 0, Double.NaN, Double.NaN,
                    "no " + unitSize + "x lots visible among " + lastAnyLots
                            + " listings: the page sorts by listing total, so stacks sit deeper. "
                            + "Refusing to price a stack against 1x lots.");
        }
        if (levels.isEmpty()) {
            long opening = Math.max(cfg.minPrice(), cfg.fallbackPrice());
            discoveredAsk = Liquidity.discoveryAsk(discoveredAsk, medianRecentHoldSeconds(),
                    secondsSinceFill(), opening, sanityMaxAsk(), cfg);
            return new Liquidity.Quote(discoveredAsk, 0, Double.NaN, Double.NaN,
                    "nobody else is selling: probing upward, "
                            + (Double.isFinite(medianRecentHoldSeconds())
                                ? "recent fills in ~" + Math.round(medianRecentHoldSeconds()) + "s"
                                : "no fills yet"));
        }
        discoveredAsk = 0;   // a real book is in front of us again; discovery restarts from it
        return atCostFloor(Liquidity.quote(levels, new ArrayList<>(ownPriceCounts.keySet()),
                clearRate, unitSize, cfg));
    }

    /**
     * Refuses to quote below what the inventory cost.
     *
     * <p>Applied to whatever the book-reading produced, because the book has no idea what our
     * basis is. This is the guard that stops a poisoned calibration from selling 350/item obsidian
     * at 300.
     */
    private static Liquidity.Quote atCostFloor(Liquidity.Quote quote) {
        long avgCost = averageAcquisitionCost();
        long floor = Liquidity.costFloor(avgCost, cfg.snipeMarginPct());
        if (floor <= 0 || quote == null || quote.unitPrice() >= floor) return quote;
        long priced = Liquidity.quantize(floor, Math.max(1, cfg.tick() / Math.max(1, unitSize)));
        priced = Math.max(priced, floor);
        return new Liquidity.Quote(priced,
                Liquidity.queueAhead(competingLevels(), new ArrayList<>(ownPriceCounts.keySet()), priced),
                Double.NaN, Double.NaN,
                "held at the " + floor + "/item cost floor (paid ~" + avgCost
                        + "/item) rather than the book's " + quote.unitPrice());
    }

    /**
     * What a swept item can actually be resold for.
     *
     * <p>Not the live sell quote. That quote is <em>defined</em> as one grid step under the
     * current floor - useful for listing right now, useless as a buy ceiling, because it sits
     * almost exactly where the ask we are trying to buy already is. Subtracting a real profit
     * margin from a number that close to the floor produces a ceiling below the floor on any
     * book with real depth, which is most of them: obsidian sat refusing to buy 413/item asks
     * against a "resale anchor" of 412 for thirty straight minutes, every single time, because
     * 412 was arithmetically defined as 413 minus one dollar.
     *
     * <p>What a sweep should be priced against is what we could sell for <em>after</em> the sweep
     * clears the cheap tail - and the desk already derives exactly that number from evidence:
     * {@link Desk.Calibration}'s {@code fallbackPrice} is p90 of every realised trade, a level the
     * book has demonstrably cleared before, guarded against incoherent history by
     * {@link Desk#MAX_COHERENT_DISPERSION} and a {@value Desk#MIN_SAMPLES}-trade minimum. That is
     * real evidence about achievable resale value; the live undercut-quote never was.
     *
     * <p>The book quote still matters as a floor under the anchor - never resell for less than we
     * could get by simply undercutting right now - and a cap against our own realised sale median
     * still guards against a calibration skewed by a handful of outlier sales, the same failure
     * mode a listing at $473,600 came from originally.
     */
    private static long sweepResaleAsk(Liquidity.Quote quote) {
        long booked = quote == null ? 0 : quote.unitPrice();

        long anchor = booked;
        if (lastCalibration != null && lastCalibration.ladderReady()) {
            long evidenced = lastCalibration.cfg().fallbackPrice();
            anchor = Math.max(anchor, evidenced);
        }
        if (anchor <= 0) return 0;   // no comparable lots and no calibration: nothing to price against

        // Persisted receipts still hold a veto: if we have ever really sold this item, a sweep will
        // not price above a multiple of what we were actually paid, calibration or not.
        long realised = realisedSaleHistory();
        if (realised > 0 && anchor > realised * RESALE_OPTIMISM_LIMIT) {
            long capped = realised * RESALE_OPTIMISM_LIMIT;
            trace("resale anchor capped at " + capped + "/item: evidence suggested " + anchor
                    + " but we have only ever been paid around " + realised);
            return capped;
        }
        return anchor;
    }

    /**
     * Median per-item price we have really been paid for this item, across every session.
     *
     * <p>Read from the receipt log, not from session counters. Counters are cleared on a desk
     * switch - correctly, since a clearing rate belongs to one desk - but realised prices are
     * durable evidence, and clearing them left the buy side with nothing to reason from.
     */
    private static long realisedSaleHistory() {
        History.Samples samples = History.load(itemId, unitSize, 200);
        if (samples == null || samples.sold().isEmpty()) return 0;
        long median = History.quantile(samples.sold(), 0.50);
        return median > 0 && median != Long.MIN_VALUE ? median : 0;
    }

    /** Realised average unit price of our own sales; the only resale evidence that is ours. */
    private static long realisedSaleMedian() {
        if (sold <= 0 || grossSold <= 0) return 0;
        long items = (long) sold * Math.max(1, unitSize);
        return items > 0 ? Math.round(grossSold / (double) items) : 0;
    }

    /**
     * The desk's book as a price ladder: level, size, and cumulative depth.
     *
     * <p>This is the data every decision here is made from - the sweep ceiling, the regime, the
     * quote and the order price - so it is worth being able to read directly. Depth is cumulative
     * from the cheapest ask upward, which is the order a buy order fills in, so the line where
     * cumulative cost crosses the risk cap is the order that should be posted.
     */
    private static List<String> bookLadder() {
        List<Liquidity.Level> levels = new ArrayList<>(competingLevels());
        if (levels.isEmpty()) {
            return List.of("[EchestMM] " + itemId + ": no book read yet (or nobody is selling)");
        }
        levels.sort((a, b) -> Long.compare(a.unitPrice(), b.unitPrice()));
        long avgCost = averageAcquisitionCost();
        Liquidity.Quote quote = lastQuote;
        List<String> out = new ArrayList<>();
        out.add("[EchestMM] " + itemId + " book: " + Liquidity.classify(levels)
                + ", our ask " + (quote == null ? "n/a" : quote.unitPrice() + "/item")
                + (avgCost > 0 ? ", basis " + avgCost + "/item" : ", no basis yet"));
        int cumUnits = 0;
        long cumCost = 0;
        int shown = 0;
        for (Liquidity.Level level : levels) {
            cumUnits += level.units();
            cumCost += level.unitPrice() * (long) level.units();
            if (shown++ >= BOOK_LADDER_LINES) continue;
            String mine = ownPriceCounts.containsKey(level.unitPrice()) ? " <- ours" : "";
            out.add(String.format(Locale.ROOT, "  %,7d/item  %2d listings  %5d units  depth %,6d units / $%,d%s",
                    level.unitPrice(), level.listings(), level.units(), cumUnits, cumCost, mine));
        }
        if (levels.size() > BOOK_LADDER_LINES) {
            out.add("  ... " + (levels.size() - BOOK_LADDER_LINES) + " deeper levels, "
                    + cumUnits + " units total");
        }
        return out;
    }

    /** Realised average cost per item across everything this desk has acquired. */
    private static long averageAcquisitionCost() {
        long items = 0;
        long spend = 0;
        if (acquired > 0) {
            items += (long) acquired * Math.max(1, unitSize);
            spend += acquiredCost;
        }
        if (bought > 0) {
            items += (long) bought * Math.max(1, unitSize);
            spend += grossBought;
        }
        if (orderedItems > 0) {
            items += orderedItems;
            spend += orderedCost;
        }
        return items > 0 ? Math.round(spend / (double) items) : 0;
    }

    /** Median hold time of the last few fills; the underpricing signal. */
    private static double medianRecentHoldSeconds() {
        if (fills.isEmpty()) return Double.NaN;
        int from = Math.max(0, fills.size() - 5);
        List<Double> holds = new ArrayList<>();
        for (int i = from; i < fills.size(); i++) holds.add(fills.get(i).holdSeconds());
        holds.sort(null);
        return holds.get(holds.size() / 2);
    }

    private static double secondsSinceFill() {
        if (lastFillAtMs <= 0L) return -1.0;
        return (System.currentTimeMillis() - lastFillAtMs) / 1000.0;
    }

    /** Discovery may probe well past realised prices, but not without limit. */
    private static long sanityMaxAsk() {
        long anchor = Math.max(cfg.fallbackPrice(), cfg.minPrice());
        return anchor * ASK_DISCOVERY_MAX_MULTIPLE;
    }

    /** Our own resting ask per item, which is what a bid has to stay clear of. */
    private static long askInForce() {
        if (!ownPriceCounts.isEmpty()) return ownPriceCounts.firstKey();
        return lastQuote == null ? 0 : lastQuote.unitPrice();
    }

    /**
     * Decides whether the next action is a purchase, and at what ceiling. Three reasons to buy,
     * strongest ceiling wins: the bid ladder (obsidian), a snipe far under our own quote, and a
     * floor lift that clears the cheap tail.
     */
    private static boolean planBuy() {
        buyReason = "";
        buyBudgetRemaining = 0;
        lastLift = Liquidity.LiftPlan.no("not evaluated");
        // ordering counts as a buy channel: without it here, a desk configured to acquire only
        // through orders reported "no buy side" and did nothing.
        if (!sniping && !lifting && !acquiring && !ordering) {
            return noBuy("no acquisition channel is on (sniping/lifting/acquiring/ordering all false)");
        }
        if (cash <= 0) {
            return noBuy("cash unknown or zero (cash=" + cash + "); waiting on a /bal reply");
        }
        if (inventoryRoom(Minecraft.getInstance()) <= 0) {
            return noBuy("no inventory room to hold an acquisition");
        }

        // Acquisition prices against every lot in the book, not only stack-sized ones: a cheap
        // 17-item listing is cheap obsidian, and orders and quickbuys both take it happily.
        List<Liquidity.Level> levels = supplyLevels();
        if (levels.isEmpty()) {
            return noBuy("no book read yet (" + lastAnyLots + " " + itemId + " lots seen last scan)");
        }
        Liquidity.Quote quote = quoteNow();
        lastQuote = quote;
        long floor = levels.getFirst().unitPrice();

        // A resale anchor shared by every acquisition channel. Snipe and lift used to be priced
        // off the sell-side quote directly, which is 0 whenever the sell side has no stack-sized
        // lots to compare against - even when supplyLevels() (any lot size) has plenty to buy.
        // That silently starved quickbuy and the floor lift on exactly the sessions where paging
        // was needed to find real supply. One anchor, one piece of evidence, for every channel.
        long resaleAnchor = sweepResaleAsk(quote);

        // Acquisition by order, which supersedes clicking when it is available: one posted order
        // fills cheapest-first across the whole tail, where the click path spent an action per
        // listing and lost most of those races anyway. The pacer ceiling is fixed, so the way to
        // deploy more capital per minute is to spend fewer actions doing it.
        if (ordering && !OrderFlow.busy()) {
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastOrderMs >= ORDER_REPLAN_MS) {
                // The replan cadence must hold on a refusal too, not only a success. Advancing
                // lastOrderMs only inside sweep.go() meant a refused sweep re-entered this branch
                // on literally every tick planBuy() ran - hundreds of identical "refused" lines a
                // second, all saying the same thing, drowning the log this fix exists to keep
                // readable.
                lastOrderMs = nowMs;
                int heldUnits = countInventory(Minecraft.getInstance());
                int roomUnits = maxHoldUnits > 0
                        ? Math.max(0, maxHoldUnits * Math.max(1, unitSize) - heldUnits)
                        : Integer.MAX_VALUE;
                long orderCash = Math.min(cash, Math.round(maxOrderCost / Math.max(0.01, cfg.maxCashSharePct())));
                Sweep.Plan sweep = resaleAnchor <= 0
                        ? Sweep.Plan.no("no evidenced resale price yet: sell a few units first, "
                                + "then the sweep has something to price against")
                        : Sweep.plan(levels, resaleAnchor, orderCash, roomUnits, minOrderProfit, cfg);
                if (sweep.go() && sweep.modelledCost() > maxOrderCost) {
                    sweep = Sweep.Plan.no("modelled cost " + sweep.modelledCost()
                            + " exceeds the " + maxOrderCost + " max order size");
                }
                lastSweep = sweep;
                if (sweep.go()) {
                    buyReason = "order sweep: " + sweep.reason();
                    trace(buyReason);
                    OrderFlow.place(itemId, deskItemName(), sweep.units(), sweep.ceilingPrice());
                    return false;   // the order flow owns the GUI until it finishes
                }
                trace("order sweep refused: " + sweep.reason());
            }
        }

        long ladderAt = 0;
        if (acquiring) {
            int heldUnits = countInventory(Minecraft.getInstance());
            int heldListings = heldUnits / Math.max(1, unitSize);
            if (maxHoldUnits > 0 && heldListings >= maxHoldUnits) {
                lastBid = new Liquidity.BidPlan(false, currentBid,
                        "holding " + heldListings + " listings' worth, at the " + maxHoldUnits + " cap");
            } else {
                long nowMs = System.currentTimeMillis();
                boolean quiet = lastProbeMs > 0 && nowMs - lastProbeMs >= probeSeconds * 1000L;
                Liquidity.BidPlan plan = Liquidity.ladderBid(currentBid, bidCeiling, askInForce(),
                        quiet, lastProbeFilled, bidStart, bidStep, minSpreadPct, cfg);
                lastBid = plan;
                if (plan.buy()) {
                    if (plan.bid() != currentBid || quiet) {
                        trace("ladder: " + plan.reason());
                        lastProbeMs = nowMs;
                        lastProbeFilled = false;
                    }
                    currentBid = plan.bid();
                    ladderAt = currentBid;
                }
            }
        }

        // Snipe and lift now price off the shared resale anchor, not the possibly-empty sell
        // quote - see the comment above resaleAnchor.
        long snipeAt = sniping ? Liquidity.snipeCeiling(resaleAnchor, cfg) : 0;
        long liftAt = 0;
        if (lifting) {
            Liquidity.LiftPlan plan = Liquidity.planFloorLift(levels, resaleAnchor,
                    countInventory(Minecraft.getInstance()), Math.max(0, localFree), cash, cfg);
            lastLift = plan;
            if (plan.go()) liftAt = plan.buyUpTo();
        }

        // A live squeeze takes everything under its ask; that is the point of it.
        long squeezeAt = squeeze != null && squeeze.go() ? squeeze.sweepCeiling() : 0;
        long ceiling = Math.max(squeezeAt, Math.max(ladderAt, Math.max(snipeAt, liftAt)));
        if (ceiling <= 0 || floor > ceiling) {
            return noBuy("no channel wants to buy: floor " + floor + "/item, resale anchor "
                    + (resaleAnchor > 0 ? resaleAnchor + "/item" : "none")
                    + ", snipe ceiling " + snipeAt + ", lift " + liftAt + ", ladder " + ladderAt
                    + ", squeeze " + squeezeAt);
        }

        buyMaxPay = ceiling;   // unit price
        buyBudgetRemaining = (long) Math.floor(cash * cfg.maxCashSharePct());
        if (buyBudgetRemaining < floor) {
            return noBuy("budget " + buyBudgetRemaining + " (from $" + cash + " cash) is under "
                    + "the " + floor + "/item floor");
        }
        if (ceiling == squeezeAt) {
            buyReason = "squeeze sweep: taking everything at or under " + squeezeAt + "/item to lift "
                    + "the floor to our " + squeeze.askUnitPrice() + "/item asks";
        } else if (ceiling == ladderAt && ladderAt >= snipeAt && ladderAt >= liftAt) {
            buyReason = "ladder bid " + ladderAt + "/item vs floor " + floor + "/item (ask "
                    + askInForce() + "/item)";
        } else if (liftAt >= snipeAt) {
            buyReason = "lift floor: " + lastLift.reason();
        } else {
            buyReason = "snipe under " + snipeAt + "/item (resale anchor " + resaleAnchor + "/item)";
        }
        return true;
    }

    private static long lastNoBuyLogMs;
    private static String lastNoBuyReason = "";

    /**
     * Every reason {@link #planBuy} declines to act, on the record.
     *
     * <p>Rate-limited rather than silent or per-tick: a desk sitting in {@code WAIT_FULL} calls
     * this every tick, and logging that verbatim would drown the file. But the exact same
     * reason, unlogged, is what let a permanently-unknown cash balance disable this entire method
     * for the whole session with nothing in {@code echest.log} to show for it. Every distinct
     * reason gets logged at least once immediately, and the current reason is repeated at most
     * once every 30 seconds for as long as it holds.
     */
    private static boolean noBuy(String reason) {
        long nowMs = System.currentTimeMillis();
        if (!reason.equals(lastNoBuyReason) || nowMs - lastNoBuyLogMs > 30_000L) {
            trace("not buying: " + reason);
            lastNoBuyReason = reason;
            lastNoBuyLogMs = nowMs;
        }
        return false;
    }

    /**
     * The best-value listing at or below the bid: cheapest per item, any stack size, affordable in
     * total. Comparing totals instead of unit prices would prefer a 1x listing over a whole stack.
     */
    private static MarketFeed.Listing pickBuyTarget(MarketFeed.PageScan page) {
        MarketFeed.Listing best = null;
        long bestUnit = Long.MAX_VALUE;
        for (MarketFeed.Listing l : page.listings()) {
            if (!itemId.equals(l.itemId())) continue;
            long total = Math.round(l.totalPrice());
            if (total <= 0 || total > buyBudgetRemaining) continue;
            long unit = unitPriceOf(l.totalPrice(), l.count());
            if (unit <= 0 || unit > buyMaxPay) continue;
            if (Math.max(1, l.count()) > inventoryRoom(Minecraft.getInstance())) continue;
            if (isOwnListing(l, unit)) continue;
            if (unit < bestUnit) {
                bestUnit = unit;
                best = l;
            }
        }
        return best;
    }

    private static boolean isOwnListing(MarketFeed.Listing l, long unitPrice) {
        if (!selfName.isBlank() && !l.seller().isBlank()
                && l.seller().toLowerCase(Locale.ROOT).contains(selfName.toLowerCase(Locale.ROOT))) return true;
        return ownPriceCounts.getOrDefault(unitPrice, 0) > 0;
    }

    private static boolean acceptBuyQuote(LocalPlayer player, MarketFeed.QuoteRead quote) {
        double price = quote.price();
        if (!(price > 0.0) || !Double.isFinite(price)) {
            if (++waited > SCREEN_TIMEOUT_TICKS) {
                retryOrPause(player, null, "buy dialog price could not be read: \"" + quote.evidence() + "\"");
            }
            return false;
        }
        double tolerance = compactTolerance(quote.evidence(), price);
        if (price > buyMaxPay + tolerance) {
            trace("refusing: dialog says " + Math.round(price) + ", ceiling is " + buyMaxPay);
            closeAnyScreen();
            lastMarketTick = -1;
            next("buy quote above the ceiling");
            return false;
        }
        return true;
    }

    // ---- inventory helpers -----------------------------------------------------------------

    private static boolean isDeskItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && itemId.equals(id.toString());
    }

    private static int countInventory(Minecraft client) {
        if (client.player == null) return 0;
        InventoryMenu inv = client.player.inventoryMenu;
        int n = 0;
        for (int i = MAIN_START; i < HOTBAR_END; i++) {
            ItemStack st = inv.getSlot(i).getItem();
            if (isDeskItem(st)) n += st.getCount();
        }
        return n;
    }

    private static int inventoryRoom(Minecraft client) {
        if (client.player == null) return 0;
        InventoryMenu inv = client.player.inventoryMenu;
        int room = 0;
        for (int i = MAIN_START; i < HOTBAR_END; i++) {
            ItemStack st = inv.getSlot(i).getItem();
            if (st.isEmpty()) room += 64;
            else if (isDeskItem(st)) room += Math.max(0, st.getMaxStackSize() - st.getCount());
        }
        return room;
    }

    /** A hotbar slot 2-9 holding the desk item in the wrong quantity for one listing. */
    private static int findWrongSizedHotbarStack(InventoryMenu inv) {
        for (int s = HOTBAR_START; s < HOTBAR_END; s++) {
            ItemStack st = inv.getSlot(s).getItem();
            if (isDeskItem(st) && st.getCount() != unitSize) return s;
        }
        return -1;
    }

    private static int findHotbarUnit(InventoryMenu inv) {
        for (int s = HOTBAR_START; s < HOTBAR_END; s++) {
            ItemStack st = inv.getSlot(s).getItem();
            if (isDeskItem(st) && st.getCount() == unitSize) return s;
        }
        return -1;
    }

    private static boolean queuePutBack(LocalPlayer player, InventoryMenu inv, int fromSlot) {
        if (cursorFixes >= MAX_CURSOR_FIXES) return false;
        ItemStack stray = fromSlot < 0 ? inv.getCarried() : inv.getSlot(fromSlot).getItem();
        int dest = -1;
        for (int i = MAIN_START; i < MAIN_END; i++) {
            ItemStack st = inv.getSlot(i).getItem();
            if (st.isEmpty() || (ItemStack.isSameItemSameComponents(st, stray)
                    && st.getCount() + stray.getCount() <= st.getMaxStackSize())) { dest = i; break; }
        }
        if (dest < 0) return false;
        cursorFixes++;
        trace("stray stack of " + stray.getCount() + (fromSlot < 0 ? " on the cursor" : " in hotbar "
                + (fromSlot - 36 + 1)) + "; putting it back into slot " + dest);
        if (fromSlot >= 0) queue.add(new Click(fromSlot, 0, ContainerInput.PICKUP));
        queue.add(new Click(dest, 0, ContainerInput.PICKUP));
        return true;
    }

    /**
     * Queues the clicks that put exactly one listing unit into a free hotbar slot.
     *
     * <p>For a single item that is pick up the stack, right-click one out, put the rest back. For
     * a full-stack unit the stack moves whole, which is both fewer clicks and the correct listing
     * shape: {@code /ah sell} lists whatever is held, so a stack desk must hold exactly a stack.
     */
    private static boolean queueRefillUnit(LocalPlayer player, InventoryMenu inv) {
        int dest = -1;
        for (int s = HOTBAR_START; s < HOTBAR_END; s++) {
            if (inv.getSlot(s).getItem().isEmpty()) { dest = s; break; }
        }
        int exact = -1;
        int partial = -1;
        int oversized = -1;
        for (int i = MAIN_START; i < MAIN_END; i++) {
            ItemStack st = inv.getSlot(i).getItem();
            if (!isDeskItem(st)) continue;
            int count = st.getCount();
            if (count == unitSize && exact < 0) exact = i;
            else if (count > unitSize && oversized < 0) oversized = i;
            else if (count < unitSize && partial < 0) partial = i;
        }
        if (dest < 0) {
            if (findHotbarUnit(inv) < 0) pause(player, "hotbar slots 2-9 are occupied; free one up");
            return false;
        }
        if (exact >= 0) {
            queue.add(new Click(exact, 0, ContainerInput.PICKUP));
            queue.add(new Click(dest, 0, ContainerInput.PICKUP));
            return true;
        }
        if (oversized >= 0) {
            if (unitSize == 1) {
                queue.add(new Click(oversized, 0, ContainerInput.PICKUP));  // stack to the cursor
                queue.add(new Click(dest, 1, ContainerInput.PICKUP));       // right-click: place one
                queue.add(new Click(oversized, 0, ContainerInput.PICKUP));  // remainder back
                return true;
            }
            pause(player, "a slot holds more than one listing unit (" + unitSize + "); split it manually");
            return false;
        }
        if (findHotbarUnit(inv) < 0) {
            String have = partial >= 0 ? "only partial stacks of " + itemId : "no " + itemId;
            if (acquiring || squeezing) {
                // Out of stock is not out of work: the buy side still has to acquire (or sweep).
                // Switching the whole engine off here is what ended the obsidian run after a
                // single dispatch, and it also spun this state at 16 traces a second before that.
                setState(State.WAIT_FULL, have + " to list; the buy side keeps working");
                return false;
            }
            stop(player::sendSystemMessage, have + " left to list");
        }
        return false;
    }

    // ---- confirmation ----------------------------------------------------------------------

    private static boolean pressSellConfirm(Minecraft client, LocalPlayer player, Screen screen) {
        if (screen instanceof DialogScreen<?> dialog) {
            MarketFeed.QuoteRead quote = MarketFeed.readDialogQuote(client, dialog);
            double tolerance = compactTolerance(quote.evidence(), quote.price());
            if (!(quote.price() > 0) || Math.abs(quote.price() - currentPrice) >= tolerance) {
                pause(player, "confirm dialog price " + quote.price() + " (+/-" + tolerance
                        + ") does not match " + currentPrice + ": \"" + quote.evidence() + "\"");
                return false;
            }
            AbstractButton button = bestPositiveButton(screen.children());
            if (button == null) {
                trace("dialog has no Yes-like button: " + buttonLabels(screen.children()));
                return false;
            }
            if (!ActionPacer.charge(System.currentTimeMillis(), 1.0)) return false;
            button.onPress(null);
            return true;
        }
        if (screen instanceof AbstractContainerScreen<?> container
                && !MarketFeed.isAuctionPage(screen.getTitle().getString())) {
            AbstractContainerMenu menu = container.getMenu();
            int slot = confirmSlot(menu);
            if (slot < 0) return false;
            return clickSlot(client, player, menu, slot, 1.0);
        }
        return false;
    }

    private static final Pattern COMPACT_MONEY = Pattern.compile("(?i)\\$\\s*([0-9][0-9,]*)(?:\\.([0-9]+))?\\s*([kmbt]?)");

    /** "$ 1.9K" stands for [1900, 2000): tolerance 100. A plain number must match within 0.5. */
    static double compactTolerance(String text, double price) {
        if (text == null) return 0.5;
        Matcher m = COMPACT_MONEY.matcher(text);
        while (m.find()) {
            String whole = m.group(1).replace(",", "");
            String decimals = m.group(2) == null ? "" : m.group(2);
            String suffix = m.group(3) == null ? "" : m.group(3).toLowerCase(Locale.ROOT);
            double mult = switch (suffix) {
                case "k" -> 1e3;
                case "m" -> 1e6;
                case "b" -> 1e9;
                case "t" -> 1e12;
                default -> 1.0;
            };
            double value;
            try { value = Double.parseDouble(whole + (decimals.isEmpty() ? "" : "." + decimals)) * mult; }
            catch (NumberFormatException e) { continue; }
            if (Math.abs(value - price) > 0.5) continue;
            return mult == 1.0 ? 0.5 : mult / Math.pow(10, decimals.length());
        }
        return 0.5;
    }

    private static int confirmSlot(AbstractContainerMenu menu) {
        List<ItemStack> items = menu.getItems();
        int limit = Math.max(0, items.size() - MarketFeed.PLAYER_INVENTORY_SLOTS);
        if (limit == 0) limit = items.size();
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < limit; i++) {
            ItemStack st = items.get(i);
            if (st == null || st.isEmpty()) continue;
            int score = confirmScore(normalize(st.getDisplayName().getString()));
            if (score > bestScore) { bestScore = score; best = i; }
        }
        return bestScore >= 150 ? best : -1;
    }

    private static AbstractButton bestPositiveButton(List<? extends GuiEventListener> elements) {
        AbstractButton best = null;
        int bestScore = Integer.MIN_VALUE;
        for (GuiEventListener e : elements) {
            if (e instanceof AbstractButton b) {
                int score = confirmScore(normalize(b.getMessage().getString()));
                if (score > bestScore) { bestScore = score; best = b; }
            }
            if (e instanceof ContainerEventHandler parent) {
                AbstractButton nested = bestPositiveButton(parent.children());
                if (nested != null) {
                    int score = confirmScore(normalize(nested.getMessage().getString()));
                    if (score > bestScore) { bestScore = score; best = nested; }
                }
            }
        }
        return bestScore >= 150 ? best : null;
    }

    private static String buttonLabels(List<? extends GuiEventListener> elements) {
        StringBuilder b = new StringBuilder();
        for (GuiEventListener e : elements) {
            if (e instanceof AbstractButton btn) b.append('[').append(btn.getMessage().getString()).append("] ");
            if (e instanceof ContainerEventHandler parent) b.append(buttonLabels(parent.children()));
        }
        return b.toString();
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.toLowerCase(Locale.ROOT).replace('\u00a7', ' ').replaceAll("\\s+", " ").trim();
    }

    static int confirmScore(String label) {
        if (label == null || label.isBlank()) return Integer.MIN_VALUE;
        if (label.equals("no") || label.equals("cancel") || label.equals("decline") || label.equals("back")
                || label.startsWith("cancel ") || label.startsWith("decline ") || label.startsWith("go back")) {
            return Integer.MIN_VALUE;
        }
        if (label.equals("yes") || label.equals("confirm") || label.equals("accept")) return 200;
        if (label.equals("confirm listing") || label.equals("confirm sale") || label.equals("confirm purchase")) return 195;
        if (label.startsWith("confirm ") || label.endsWith(" confirm")) return 190;
        if (label.contains("click to confirm")) return 185;
        if (label.equals("buy") || label.equals("buy item") || label.equals("purchase")) return 180;
        if (label.equals("sell") || label.equals("list") || label.equals("sell item") || label.equals("list item")) return 175;
        if (label.startsWith("buy ") || label.startsWith("sell ") || label.startsWith("list ")) return 165;
        if (label.equals("continue") || label.equals("proceed") || label.equals("ok") || label.equals("okay")) return 150;
        return Integer.MIN_VALUE;
    }
}
