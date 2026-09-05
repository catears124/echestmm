package dev.parker.echest;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
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
 * The only class that sends anything to the server.
 *
 * <p>Two jobs, one state machine, one command budget - so the seller and the buyer can never fight
 * over the cursor or the auction screen:
 *
 * <ol>
 *   <li><b>Dump.</b> Ender chests arrive as stacks. One unit at a time is moved into a free hotbar
 *       slot, listed with {@code /ah sell PRICE} at the liquidity-derived price from
 *       {@link Liquidity#quote}, and confirmed only when the server's own dialog shows that exact
 *       price. Stacks are never listed whole.</li>
 *   <li><b>Floor work.</b> Listings priced far under our quote are bought for resale, and the
 *       cheap tail under the target floor is bought out entirely when {@link
 *       Liquidity#planFloorLift} says the modelled return clears cost - which lifts the visible
 *       floor and every subsequent ask with it.</li>
 * </ol>
 *
 * <p>Reads are cached: the own-listings screen is re-read when the free-slot count runs out, every
 * {@code OWN_REREAD_EVERY} sales, or when a receipt names a price the local tally does not know;
 * the market page is re-read every {@code MARKET_RECHECK_TICKS} and after every buy. Commands are
 * spaced by {@code commandGapTicks}, which grows whenever the server complains about speed.
 * Everything is off until {@code /echestmm on}.
 */
public final class EchestEngine {
    private static final int HOTBAR_START = 37;   // hotbar key 2; key 1 (slot 36) is never touched
    private static final int HOTBAR_END = 45;     // exclusive
    private static final int MAIN_START = 9;
    private static final int MAIN_END = 36;       // exclusive

    private static final int CLICK_INTERVAL_TICKS = 2;
    private static final int CLICK_SETTLE_TICKS = 5;
    private static final int MAX_CURSOR_FIXES = 2;
    private static final int BASE_COMMAND_GAP_TICKS = 9;   // ~450 ms; Donut's cooldown is ~400 ms
    private static final int MAX_COMMAND_GAP_TICKS = 40;
    private static final int SCREEN_TIMEOUT_TICKS = 60;
    private static final int SOLD_TIMEOUT_TICKS = 80;
    private static final int FULL_RECHECK_TICKS = 200;
    private static final int MARKET_RECHECK_TICKS = 600;
    private static final int OWN_REREAD_EVERY = 25;
    private static final int BALANCE_REFRESH_TICKS = 2400;
    private static final int BUY_WINDOW_TICKS = 2400;
    private static final int MAX_RETRIES = 3;

    private static final Pattern BALANCE = Pattern.compile(
            "(?i)balance[^0-9$]{0,16}\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmbt]?)");

    enum State {
        OFF, PAUSED,
        CHECK_OWN, WAIT_OWN_AH, WAIT_OWN_SCAN,
        CHECK_MARKET, WAIT_MARKET,
        PREPARE_SELL, SELL_CMD, WAIT_CONFIRM, WAIT_SOLD, WAIT_FULL,
        BUY_OPEN, BUY_WAIT_PAGE, BUY_CLICK, BUY_CONFIRM, BUY_SETTLE
    }

    private record Click(int slot, int button, ContainerInput input) {}

    /** A listing of ours that is live on the market, kept to measure hold time on the sale. */
    private record LiveListing(long price, long listedAtMs, int queueAhead) {}

    // ---- configuration ---------------------------------------------------------------------
    private static String itemId = "minecraft:ender_chest";
    private static String searchTerm = "ender chest";
    private static Liquidity.Config cfg = Liquidity.Config.defaults();
    private static boolean sniping = true;
    private static boolean lifting = true;
    private static int maxBuysPerWindow = 10;
    private static boolean debug = true;

    // ---- runtime ---------------------------------------------------------------------------
    private static State state = State.OFF;
    private static final ArrayDeque<Click> queue = new ArrayDeque<>();
    private static long ticks;
    private static long lastCommandTick = -1_000_000L;
    private static int commandGapTicks = BASE_COMMAND_GAP_TICKS;
    private static long requestMs;
    private static int waited;
    private static int cooldown;
    private static int retries;
    private static int cursorFixes;
    private static long lastContainerCloseTick = -1_000_000L;
    private static String pauseReason = "";

    private static int sellSlot = -1;
    private static long currentPrice;
    private static int currentQueueAhead;
    private static int sold;
    private static long grossSold;
    private static boolean listReceiptSeen;

    private static int bought;
    private static long grossBought;
    private static int buysInWindow;
    private static long buyWindowStart = -1_000_000L;
    private static int buySlot = -1;
    private static long buyMaxPay;
    private static long buyExpectedPrice;
    private static String buyReason = "";
    private static long buyBudgetRemaining;

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

    private static Liquidity.Quote lastQuote;
    private static Liquidity.LiftPlan lastLift = Liquidity.LiftPlan.no("not evaluated");

    private EchestEngine() {}

    static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(EchestEngine::tick);
        MarketFeed.onReceipt(EchestEngine::onReceipt);
        MarketFeed.onMessage(EchestEngine::onMessage);
        registerCommands();
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
                        .then(literal("debug").executes(ctx -> {
                            debug = !debug;
                            return feedback(ctx.getSource()::sendFeedback, "trace " + (debug ? "ON" : "OFF"));
                        }))
                        .then(literal("snipe").then(argument("on", BoolArgumentType.bool()).executes(ctx -> {
                            sniping = BoolArgumentType.getBool(ctx, "on");
                            return feedback(ctx.getSource()::sendFeedback, "sniping " + (sniping ? "ON" : "OFF"));
                        })))
                        .then(literal("lift").then(argument("on", BoolArgumentType.bool()).executes(ctx -> {
                            lifting = BoolArgumentType.getBool(ctx, "on");
                            return feedback(ctx.getSource()::sendFeedback, "floor lifting " + (lifting ? "ON" : "OFF"));
                        })))
                        .then(literal("min").then(argument("price", IntegerArgumentType.integer(1)).executes(ctx -> {
                            cfg = withMin(cfg, IntegerArgumentType.getInteger(ctx, "price"));
                            return feedback(ctx.getSource()::sendFeedback, "minimum price " + cfg.minPrice());
                        })))
                        .then(literal("open").then(argument("price", IntegerArgumentType.integer(1)).executes(ctx -> {
                            cfg = withFallback(cfg, IntegerArgumentType.getInteger(ctx, "price"));
                            return feedback(ctx.getSource()::sendFeedback, "empty-book price " + cfg.fallbackPrice());
                        })))
                        .then(literal("hold").then(argument("seconds", IntegerArgumentType.integer(0)).executes(ctx -> {
                            cfg = withHold(cfg, IntegerArgumentType.getInteger(ctx, "seconds"));
                            return feedback(ctx.getSource()::sendFeedback, cfg.maxHoldSeconds() <= 0
                                    ? "hold budget OFF (pure revenue-rate pricing)"
                                    : "hold budget " + Math.round(cfg.maxHoldSeconds()) + "s");
                        })))
                        .then(literal("margin").then(argument("percent", IntegerArgumentType.integer(1, 90)).executes(ctx -> {
                            cfg = withSnipeMargin(cfg, IntegerArgumentType.getInteger(ctx, "percent") / 100.0);
                            return feedback(ctx.getSource()::sendFeedback, "snipe margin "
                                    + Math.round(cfg.snipeMarginPct() * 100) + "%");
                        })))
                        .then(literal("liftunits").then(argument("count", IntegerArgumentType.integer(1, 45)).executes(ctx -> {
                            cfg = withLiftUnits(cfg, IntegerArgumentType.getInteger(ctx, "count"));
                            return feedback(ctx.getSource()::sendFeedback, "max lift units " + cfg.maxLiftUnits());
                        })))
                        .then(literal("buys").then(argument("count", IntegerArgumentType.integer(0, 60)).executes(ctx -> {
                            maxBuysPerWindow = IntegerArgumentType.getInteger(ctx, "count");
                            return feedback(ctx.getSource()::sendFeedback, "max buys per 2 min " + maxBuysPerWindow);
                        })))
                        .then(literal("recheck").executes(ctx -> {
                            invalidateCaches();
                            return feedback(ctx.getSource()::sendFeedback, "will re-read your listings and the market");
                        }))
                )
        );
    }

    private static Liquidity.Config withMin(Liquidity.Config c, long v) {
        return new Liquidity.Config(v, c.fallbackPrice(), c.undercut(), c.maxHoldSeconds(), c.ladderStep(),
                c.snipeMarginPct(), c.liftMarginPct(), c.maxLiftUnits(), c.maxCashSharePct());
    }

    private static Liquidity.Config withFallback(Liquidity.Config c, long v) {
        return new Liquidity.Config(c.minPrice(), v, c.undercut(), c.maxHoldSeconds(), c.ladderStep(),
                c.snipeMarginPct(), c.liftMarginPct(), c.maxLiftUnits(), c.maxCashSharePct());
    }

    private static Liquidity.Config withHold(Liquidity.Config c, double v) {
        return new Liquidity.Config(c.minPrice(), c.fallbackPrice(), c.undercut(), v, c.ladderStep(),
                c.snipeMarginPct(), c.liftMarginPct(), c.maxLiftUnits(), c.maxCashSharePct());
    }

    private static Liquidity.Config withSnipeMargin(Liquidity.Config c, double v) {
        return new Liquidity.Config(c.minPrice(), c.fallbackPrice(), c.undercut(), c.maxHoldSeconds(), c.ladderStep(),
                v, c.liftMarginPct(), c.maxLiftUnits(), c.maxCashSharePct());
    }

    private static Liquidity.Config withLiftUnits(Liquidity.Config c, int v) {
        return new Liquidity.Config(c.minPrice(), c.fallbackPrice(), c.undercut(), c.maxHoldSeconds(), c.ladderStep(),
                c.snipeMarginPct(), c.liftMarginPct(), v, c.maxCashSharePct());
    }

    private static int feedback(Consumer<Component> out, String msg) {
        out.accept(Component.literal("[EchestMM] " + msg));
        return Command.SINGLE_SUCCESS;
    }

    private static String status() {
        return "state=" + state + (state == State.PAUSED ? " (" + pauseReason + ")" : "")
                + " | item=" + itemId
                + " | quote=" + (lastQuote == null ? "n/a" : lastQuote.price() + " (" + lastQuote.reason() + ")")
                + " | clear=" + (Double.isFinite(clearRate) ? String.format(Locale.ROOT, "%.3f/s", clearRate) : "unmeasured")
                + " | fills=" + fills.size()
                + " | free=" + localFree
                + " | sold=" + sold + " ($" + grossSold + ")"
                + " | bought=" + bought + " ($" + grossBought + ")"
                + " | cash=" + (cash < 0 ? "unknown" : "$" + cash)
                + " | snipe=" + (sniping ? "on" : "off") + " lift=" + (lifting ? "on" : "off")
                + " | lift=" + lastLift.reason()
                + " | gap=" + commandGapTicks * 50 + "ms";
    }

    // ---- lifecycle -------------------------------------------------------------------------

    private static void start(Consumer<Component> feedback) {
        invalidateCaches();
        retries = 0;
        waited = 0;
        cooldown = 0;
        cursorFixes = 0;
        commandGapTicks = BASE_COMMAND_GAP_TICKS;
        queue.clear();
        LocalPlayer p = Minecraft.getInstance().player;
        selfName = p == null ? "" : p.getGameProfile().name();
        setState(State.CHECK_OWN, "start");
        feedback.accept(Component.literal("[EchestMM] ON: dumping 1x " + itemId
                + " at the liquidity price (min " + cfg.minPrice() + ", empty-book " + cfg.fallbackPrice()
                + "), snipe " + (sniping ? "on" : "off") + ", lift " + (lifting ? "on" : "off")
                + ". Hotbar slots 2-9 are used. /echestmm to stop."));
    }

    private static void stop(Consumer<Component> feedback, String why) {
        setState(State.OFF, why);
        queue.clear();
        feedback.accept(Component.literal("[EchestMM] OFF (" + why + "). Sold " + sold + " for $" + grossSold
                + ", bought " + bought + " for $" + grossBought + "."));
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

    private static void setState(State next, String why) {
        if (state == next) return;
        trace(state + " -> " + next + (why == null || why.isEmpty() ? "" : "  (" + why + ")"));
        state = next;
        waited = 0;
        queue.clear();   // a half-finished click sequence must never resume in another state
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
                if (r.count() != 1) {
                    LocalPlayer p = Minecraft.getInstance().player;
                    if (p != null) pause(p, "server listed " + r.count() + " items in one listing: \"" + r.raw() + "\"");
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
        if (state == State.OFF) return;
        switch (m.kind()) {
            case "RATE_LIMIT" -> {
                commandGapTicks = Math.min(MAX_COMMAND_GAP_TICKS, commandGapTicks + 4);
                lastCommandTick = ticks;
                trace("rate-limited by the server; command gap now " + commandGapTicks * 50 + " ms");
            }
            case "AH_FULL" -> {
                if (state == State.SELL_CMD || state == State.WAIT_CONFIRM || state == State.WAIT_SOLD) {
                    localFree = 0;
                    ownDirty = true;
                    retries = 0;
                    lastCommandTick = ticks;
                    closeAnyScreen();
                    enterFull("server: too many listed items");
                }
            }
            case "ALREADY_BOUGHT" -> {
                if (state == State.BUY_CONFIRM || state == State.BUY_CLICK || state == State.BUY_SETTLE) {
                    trace("lost the race for slot " + buySlot + "; re-reading the market");
                    closeAnyScreen();
                    lastMarketTick = -1;
                    lastCommandTick = ticks;
                    next("cheap listing was already bought");
                }
            }
            case "NO_FUNDS" -> {
                cash = 0;
                lastBalanceTick = -1_000_000L;
                trace("server says there is not enough money; buying is on hold until /bal refreshes");
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
                }
            }
            default -> { }
        }
    }

    private static void onOwnSale(MarketFeed.Receipt r) {
        if (!namesSellItem(r.itemName())) return;
        if (localFree >= 0) localFree++;
        long price = Math.round(r.totalPrice());
        sold++;
        grossSold += price;
        Integer n = ownPriceCounts.get(price);
        if (n != null && n > 0) {
            if (n == 1) ownPriceCounts.remove(price);
            else ownPriceCounts.put(price, n - 1);
        } else {
            ownDirty = true;
            trace("sale at " + price + " was not in the local tally; will re-read your listings");
        }
        recordFill(price, r.observedAtMs());
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
        trace("filled " + price + " after " + Math.round(holdSeconds) + "s from queue " + match.queueAhead
                + "; clearing rate " + String.format(Locale.ROOT, "%.3f", clearRate) + " units/s");
    }

    private static void onOwnPurchase(MarketFeed.Receipt r) {
        if (!namesSellItem(r.itemName())) return;
        long price = Math.round(r.totalPrice());
        bought += Math.max(1, r.count());
        grossBought += price;
        if (cash >= 0) cash = Math.max(0, cash - price);
        buyBudgetRemaining = Math.max(0, buyBudgetRemaining - price);
        lastMarketTick = -1;    // the book changed underneath us
        trace("bought " + r.count() + "x at " + price + " (" + buyReason + ")");
        if (state == State.BUY_CONFIRM || state == State.BUY_SETTLE) {
            closeAnyScreen();
            lastCommandTick = ticks;
            next("bought a cheap listing");
        }
    }

    /** "Ender Chest" names minecraft:ender_chest. */
    private static boolean namesSellItem(String itemName) {
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

    // ---- main loop -------------------------------------------------------------------------

    private static boolean needOwnRead() {
        return localFree < 0 || ownDirty || salesSinceOwnRead >= OWN_REREAD_EVERY;
    }

    private static boolean needMarketRead() {
        return lastMarketTick < 0 || ticks - lastMarketTick >= MARKET_RECHECK_TICKS;
    }

    private static boolean needBalance() {
        return (sniping || lifting) && (cash < 0 || ticks - lastBalanceTick >= BALANCE_REFRESH_TICKS);
    }

    /** The single dispatcher after every read, sale or purchase. */
    private static void next(String why) {
        if (needOwnRead()) setState(State.CHECK_OWN, why + "; need your-listings read");
        else if (needMarketRead()) setState(State.CHECK_MARKET, why + "; need market read");
        else if (planBuy()) setState(State.BUY_OPEN, why + "; " + buyReason);
        else if (localFree == 0) enterFull(why);
        else setState(State.PREPARE_SELL, why);
    }

    private static void enterFull(String why) {
        if (state == State.WAIT_FULL) return;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            p.sendSystemMessage(Component.literal("[EchestMM] all listing slots are taken; waiting for a sale"));
        }
        setState(State.WAIT_FULL, why);
    }

    private static void tick(Minecraft client) {
        ticks++;
        LocalPlayer player = client.player;
        if (state == State.OFF || state == State.PAUSED || player == null || client.gameMode == null
                || client.getConnection() == null) return;
        if (cooldown > 0) { cooldown--; return; }
        InventoryMenu inv = player.inventoryMenu;
        Screen screen = client.gui.screen();

        switch (state) {
            case CHECK_OWN -> {
                if (needBalance() && !balanceRequested && readyForCommand(player, inv, screen)) {
                    balanceRequested = true;
                    sendCommand(client, "bal");
                    return;
                }
                if (!readyForCommand(player, inv, screen)) return;
                sendCommand(client, "ah");
                requestMs = System.currentTimeMillis();
                setState(State.WAIT_OWN_AH, "sent /ah");
            }
            case WAIT_OWN_AH -> {
                if (screen instanceof AbstractContainerScreen<?> container
                        && MarketFeed.isAuctionPage(screen.getTitle().getString())) {
                    AbstractContainerMenu menu = container.getMenu();
                    int top = menu.getItems().size() - MarketFeed.PLAYER_INVENTORY_SLOTS;
                    if (top < 9) { pause(player, "unexpected auction layout (" + top + " top slots)"); return; }
                    int chestSlot = top - 9 + 6;   // last row, 7th slot: "Your Items"
                    client.gameMode.handleContainerInput(menu.containerId, chestSlot, 0, ContainerInput.PICKUP, player);
                    requestMs = System.currentTimeMillis();
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
                    lastCommandTick = ticks;
                    retries = 0;
                    next(localFree + " free, " + ownTotal() + " of ours live");
                } else if (++waited > SCREEN_TIMEOUT_TICKS) {
                    retryOrPause(player, screen, "Your Items screen did not open or could not be read");
                }
            }
            case CHECK_MARKET -> {
                if (!readyForCommand(player, inv, screen)) return;
                sendCommand(client, "ah " + searchTerm);
                requestMs = System.currentTimeMillis();
                setState(State.WAIT_MARKET, "sent /ah " + searchTerm);
            }
            case WAIT_MARKET -> {
                MarketFeed.PageScan page = MarketFeed.latestPage();
                if (page != null && page.observedAtMs() >= requestMs && page.page() == 1) {
                    boolean ok = readMarket(player, page);
                    closeScreen(player, screen);
                    lastCommandTick = ticks;
                    if (ok) { retries = 0; next("market read"); }
                } else if (++waited > SCREEN_TIMEOUT_TICKS) {
                    retryOrPause(player, screen, "market page did not load");
                }
            }
            case WAIT_FULL -> {
                if (++waited >= FULL_RECHECK_TICKS) {
                    localFree = -1;
                    setState(State.CHECK_OWN, "re-check after wait");
                }
            }
            case BUY_OPEN -> {
                if (!readyForCommand(player, inv, screen)) return;
                sendCommand(client, "ah " + searchTerm);
                requestMs = System.currentTimeMillis();
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
                    closeScreen(player, screen);
                    lastCommandTick = ticks;
                    next("nothing cheap enough left on page 1");
                    return;
                }
                buySlot = target.slot();
                buyExpectedPrice = Math.round(target.totalPrice());
                client.gameMode.handleContainerInput(container.getMenu().containerId, buySlot, 0,
                        ContainerInput.PICKUP, player);
                requestMs = System.currentTimeMillis();
                setState(State.BUY_CONFIRM, "clicked listing at " + buyExpectedPrice + " (" + buyReason + ")");
            }
            case BUY_CLICK -> next("buy click state is unused");
            case BUY_CONFIRM -> {
                if (screen instanceof DialogScreen<?> dialog) {
                    MarketFeed.QuoteRead quote = MarketFeed.readDialogQuote(client, dialog);
                    if (!confirmBuyQuote(player, quote)) return;
                    AbstractButton button = bestPositiveButton(screen.children());
                    if (button == null) {
                        if (++waited > SCREEN_TIMEOUT_TICKS) {
                            retryOrPause(player, screen, "buy dialog has no Yes-like button: " + buttonLabels(screen.children()));
                        }
                        return;
                    }
                    button.onPress(null);
                    requestMs = System.currentTimeMillis();
                    setState(State.BUY_SETTLE, "confirmed buy at or below " + buyMaxPay);
                    return;
                }
                if (screen instanceof AbstractContainerScreen<?> container
                        && !MarketFeed.isAuctionPage(screen.getTitle().getString())) {
                    MarketFeed.QuoteRead quote = MarketFeed.readContainerQuote(client, container);
                    if (!confirmBuyQuote(player, quote)) return;
                    int slot = confirmSlot(container.getMenu());
                    if (slot < 0) {
                        if (++waited > SCREEN_TIMEOUT_TICKS) retryOrPause(player, screen, "no confirm slot in the buy chest");
                        return;
                    }
                    client.gameMode.handleContainerInput(container.getMenu().containerId, slot, 0,
                            ContainerInput.PICKUP, player);
                    requestMs = System.currentTimeMillis();
                    setState(State.BUY_SETTLE, "confirmed buy at or below " + buyMaxPay);
                    return;
                }
                if (++waited > SCREEN_TIMEOUT_TICKS) {
                    retryOrPause(player, screen, "no buy confirmation surface appeared");
                }
            }
            case BUY_SETTLE -> {
                // A BUY receipt or an ALREADY_BOUGHT message normally moves us on; this is the timeout.
                if (++waited > SOLD_TIMEOUT_TICKS) {
                    closeScreen(player, screen);
                    lastCommandTick = ticks;
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
                    Click c = queue.poll();
                    client.gameMode.handleContainerInput(inv.containerId, c.slot, c.button, c.input, player);
                    cooldown = CLICK_INTERVAL_TICKS;
                    return;
                }
                // Right after a container closes the server is resyncing the inventory; clicks sent
                // then are dropped or misapplied and strand a stack on the cursor.
                boolean settled = ticks - lastContainerCloseTick >= CLICK_SETTLE_TICKS;
                if (!inv.getCarried().isEmpty()) {
                    if (!settled) return;
                    if (!queuePutBack(player, inv, -1)) pause(player, "the cursor is holding an item");
                    return;
                }
                int stackSlot = findHotbarStack(inv);
                if (stackSlot >= 0) {
                    if (!settled) return;
                    if (!queuePutBack(player, inv, stackSlot)) {
                        pause(player, "hotbar slot " + (stackSlot - 36 + 1) + " holds a stack of "
                                + inv.getSlot(stackSlot).getItem().getCount() + "; move it out of the hotbar");
                    }
                    return;
                }
                int slot = findHotbarSingle(inv);
                long gapLeft = commandGapTicks - (ticks - lastCommandTick);
                if (settled && (slot < 0 || gapLeft >= 3L * CLICK_INTERVAL_TICKS)) {
                    if (queueRefillOne(player, inv)) return;   // splits one unit off a stack
                    if (slot < 0) return;                      // stopped or paused inside the refill
                }
                if (slot < 0 || gapLeft > 0) return;
                Liquidity.Quote quote = quoteNow();
                lastQuote = quote;
                if (quote.price() < cfg.minPrice()) {
                    pause(player, "computed price " + quote.price() + " is under the minimum " + cfg.minPrice());
                    return;
                }
                currentPrice = quote.price();
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
                if (!isSellItem(held) || held.getCount() != 1
                        || player.getInventory().getSelectedSlot() != sellSlot - 36) {
                    pause(player, "the held slot changed before listing (count=" + held.getCount() + ")");
                    return;
                }
                listReceiptSeen = false;
                sendCommand(client, "ah sell " + currentPrice);
                setState(State.WAIT_CONFIRM, "sent /ah sell " + currentPrice);
            }
            case WAIT_CONFIRM -> {
                if (!isSellItem(inv.getSlot(sellSlot).getItem())) { onListed(player, screen); return; }
                if (screen != null && pressSellConfirm(client, player, screen)) {
                    setState(State.WAIT_SOLD, "pressed confirm");
                } else if (++waited > SCREEN_TIMEOUT_TICKS) {
                    retryOrPause(player, screen, "no confirmation screen after /ah sell");
                }
            }
            case WAIT_SOLD -> {
                if (!isSellItem(inv.getSlot(sellSlot).getItem())) { onListed(player, screen); return; }
                if (++waited > SOLD_TIMEOUT_TICKS) retryOrPause(player, screen, "confirmed but the item is still in the hotbar");
            }
            default -> { }
        }
    }

    private static boolean readyForCommand(LocalPlayer player, InventoryMenu inv, Screen screen) {
        if (screen != null) { closeScreen(player, screen); cooldown = 3; return false; }
        if (player.containerMenu != inv) {
            if (++waited % 40 == 0) trace("waiting: a server-side container is still open");
            return false;
        }
        return ticks - lastCommandTick >= commandGapTicks;
    }

    /** One unit is now live on the market. */
    private static void onListed(LocalPlayer player, Screen screen) {
        salesSinceOwnRead++;
        if (localFree > 0) localFree--;
        if (localFree == 0) localFree = -1;   // reached zero by counting: verify with a read
        ownPriceCounts.merge(currentPrice, 1, Integer::sum);
        liveListings.add(new LiveListing(currentPrice, System.currentTimeMillis(), currentQueueAhead));
        if (liveListings.size() > 64) liveListings.removeFirst();
        if (screen != null) closeScreen(player, screen);
        lastCommandTick = ticks;
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
        lastCommandTick = ticks;
        invalidateCaches();
        setState(State.CHECK_OWN, "retry " + retries);
    }

    private static void sendCommand(Minecraft client, String command) {
        client.getConnection().sendCommand(command);
        lastCommandTick = ticks;
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
            if (itemId.equals(l.itemId()) && l.count() == 1 && Double.isFinite(l.totalPrice())) {
                ownPriceCounts.merge(Math.round(l.totalPrice()), 1, Integer::sum);
            }
        }
        // Drop live-listing records the server no longer shows, so hold times stay honest.
        liveListings.removeIf(live -> !ownPriceCounts.containsKey(live.price));
    }

    /** Caches page-1 single-unit listing counts by price. Returns false after pausing. */
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
        marketCounts.clear();
        int singles = 0;
        for (MarketFeed.Listing l : all) {
            if (itemId.equals(l.itemId()) && l.count() == 1 && l.totalPrice() > 0) {
                marketCounts.merge(Math.round(l.totalPrice()), 1, Integer::sum);
                singles++;
            }
        }
        lastMarketTick = ticks;
        trace("market: " + singles + " single " + itemId + " listings on page 1"
                + (marketCounts.isEmpty() ? "" : ", floor " + marketCounts.firstKey()));
        return true;
    }

    /** Competing levels: the visible book minus our own listings at the same price. */
    private static List<Liquidity.Level> competingLevels() {
        List<Liquidity.Level> levels = new ArrayList<>(marketCounts.size());
        for (var e : marketCounts.entrySet()) {
            int mine = ownPriceCounts.getOrDefault(e.getKey(), 0);
            int others = e.getValue() - mine;
            if (others > 0) levels.add(new Liquidity.Level(e.getKey(), others));
        }
        return levels;
    }

    private static Liquidity.Quote quoteNow() {
        return Liquidity.quote(competingLevels(), new ArrayList<>(ownPriceCounts.keySet()), clearRate, cfg);
    }

    /**
     * Decides whether the next action is a purchase. Sniping buys anything priced far below our
     * own quote; lifting buys the whole cheap tail so the floor - and our ask - moves up.
     */
    private static boolean planBuy() {
        buyReason = "";
        buyBudgetRemaining = 0;
        lastLift = Liquidity.LiftPlan.no("not evaluated");
        if (!sniping && !lifting) return false;
        if (maxBuysPerWindow <= 0 || cash <= 0) return false;
        if (ticks - buyWindowStart >= BUY_WINDOW_TICKS) { buyWindowStart = ticks; buysInWindow = 0; }
        if (buysInWindow >= maxBuysPerWindow) return false;
        if (inventoryRoom(Minecraft.getInstance()) <= 0) return false;

        List<Liquidity.Level> levels = competingLevels();
        if (levels.isEmpty()) return false;
        Liquidity.Quote quote = quoteNow();
        lastQuote = quote;

        long snipeAt = sniping ? Liquidity.snipeCeiling(quote.price(), cfg) : 0;
        long liftAt = 0;
        if (lifting) {
            int held = countInventory(Minecraft.getInstance());
            Liquidity.LiftPlan plan = Liquidity.planFloorLift(levels, quote.price(), held,
                    Math.max(0, localFree), cash, cfg);
            lastLift = plan;
            if (plan.go()) liftAt = plan.buyUpTo();
        }

        long ceiling = Math.max(snipeAt, liftAt);
        if (ceiling <= 0) return false;
        long floor = levels.getFirst().price();
        if (floor > ceiling) return false;

        buyMaxPay = ceiling;
        buyBudgetRemaining = (long) Math.floor(cash * cfg.maxCashSharePct());
        buyReason = liftAt >= floor && liftAt >= snipeAt
                ? "lift floor: " + lastLift.reason()
                : "snipe: floor " + floor + " is under the snipe ceiling " + ceiling + " (quote " + quote.price() + ")";
        return true;
    }

    /** The cheapest listing at or below {@code buyMaxPay} that is not one of ours. */
    private static MarketFeed.Listing pickBuyTarget(MarketFeed.PageScan page) {
        MarketFeed.Listing best = null;
        for (MarketFeed.Listing l : page.listings()) {
            if (!itemId.equals(l.itemId()) || l.count() != 1) continue;
            long price = Math.round(l.totalPrice());
            if (price <= 0 || price > buyMaxPay) continue;
            if (price > buyBudgetRemaining) continue;
            if (isOwnListing(l, price)) continue;
            if (best == null || price < Math.round(best.totalPrice())) best = l;
        }
        return best;
    }

    private static boolean isOwnListing(MarketFeed.Listing l, long price) {
        if (!selfName.isBlank() && !l.seller().isBlank()
                && l.seller().toLowerCase(Locale.ROOT).contains(selfName.toLowerCase(Locale.ROOT))) return true;
        // Seller metadata is sometimes missing; a price we hold is treated as possibly ours.
        return ownPriceCounts.getOrDefault(price, 0) > 0;
    }

    private static boolean confirmBuyQuote(LocalPlayer player, MarketFeed.QuoteRead quote) {
        double price = quote.price();
        if (!(price > 0.0) || !Double.isFinite(price)) {
            if (++waited > SCREEN_TIMEOUT_TICKS) {
                retryOrPause(player, null, "buy dialog price could not be read: \"" + quote.evidence() + "\"");
            }
            return false;
        }
        double tolerance = compactTolerance(quote.evidence(), price);
        if (price > buyMaxPay + tolerance) {
            trace("refusing the buy: dialog says " + Math.round(price) + " but the ceiling is " + buyMaxPay);
            closeAnyScreen();
            lastCommandTick = ticks;
            lastMarketTick = -1;
            next("buy quote above the ceiling");
            return false;
        }
        buysInWindow++;
        return true;
    }

    // ---- inventory helpers -----------------------------------------------------------------

    private static boolean isSellItem(ItemStack stack) {
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
            if (isSellItem(st)) n += st.getCount();
        }
        return n;
    }

    /** Free item slots left for bought stock. */
    private static int inventoryRoom(Minecraft client) {
        if (client.player == null) return 0;
        InventoryMenu inv = client.player.inventoryMenu;
        int room = 0;
        for (int i = MAIN_START; i < HOTBAR_END; i++) {
            ItemStack st = inv.getSlot(i).getItem();
            if (st.isEmpty()) room += 64;
            else if (isSellItem(st)) room += Math.max(0, st.getMaxStackSize() - st.getCount());
        }
        return room;
    }

    /** A hotbar slot 2-9 holding two or more; selling from one would list the whole stack. */
    private static int findHotbarStack(InventoryMenu inv) {
        for (int s = HOTBAR_START; s < HOTBAR_END; s++) {
            ItemStack st = inv.getSlot(s).getItem();
            if (isSellItem(st) && st.getCount() > 1) return s;
        }
        return -1;
    }

    private static int findHotbarSingle(InventoryMenu inv) {
        for (int s = HOTBAR_START; s < HOTBAR_END; s++) {
            ItemStack st = inv.getSlot(s).getItem();
            if (isSellItem(st) && st.getCount() == 1) return s;
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
     * Queues the three clicks that split exactly one unit off a stack into a free hotbar slot:
     * pick the stack up, right-click to drop one, put the remainder back. This is how a stack of
     * ender chests becomes a sequence of single-unit listings.
     */
    private static boolean queueRefillOne(LocalPlayer player, InventoryMenu inv) {
        int dest = -1;
        for (int s = HOTBAR_START; s < HOTBAR_END; s++) {
            if (inv.getSlot(s).getItem().isEmpty()) { dest = s; break; }
        }
        int src = -1;
        for (int i = MAIN_START; i < MAIN_END; i++) {
            if (isSellItem(inv.getSlot(i).getItem())) { src = i; break; }
        }
        if (dest < 0 || src < 0) {
            if (findHotbarSingle(inv) < 0) {
                if (src < 0) stop(player::sendSystemMessage, "no " + itemId + " left in the inventory");
                else pause(player, "hotbar slots 2-9 are occupied by other items; free one up");
            }
            return false;
        }
        queue.add(new Click(src, 0, ContainerInput.PICKUP));   // whole stack to the cursor
        queue.add(new Click(dest, 1, ContainerInput.PICKUP));  // right-click: place exactly one
        queue.add(new Click(src, 0, ContainerInput.PICKUP));   // remainder back
        return true;
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
            button.onPress(null);
            return true;
        }
        if (screen instanceof AbstractContainerScreen<?> container
                && !MarketFeed.isAuctionPage(screen.getTitle().getString())) {
            AbstractContainerMenu menu = container.getMenu();
            int slot = confirmSlot(menu);
            if (slot < 0) return false;
            client.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, player);
            return true;
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
