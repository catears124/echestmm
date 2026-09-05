package dev.parker.echest;

import dev.parker.echest.mixin.DialogScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Places and collects DonutSMP buy orders.
 *
 * <p>Every step here was recorded from a hand walkthrough rather than guessed - see
 * {@link FlowRecorder}. The observed contract, with the exact titles the server sends:
 *
 * <pre>
 * /orders                    -> "Orders (Page 1)"        90 slots, page 1 is everyone's bid book
 *   slot 51                  -> "Orders -&gt; Your Orders"   grey "[New Order]" panes are free slots
 *   a [New Order] slot       -> dialog "Choose Item"      editbox hint "Search" + "Search" button
 *   type name, press Search  -> "Choose Item (N results)" buttons like "[block/obsidian] Obsidian"
 *   press the exact item     -> dialog "How many?"        editbox "Amount", button "Next"
 *   type amount, press Next  -> dialog "Price per item?"  editbox "Price", button "Review Order"
 *   type price, press Review -> dialog "Review Order"     Item/Amount/Price/Total, "Create Order"
 *   press Create Order       -> chat "You ordered N Item"
 * </pre>
 *
 * <p>Collection, also recorded: {@code slot 51 -> your order -> "Edit Order" -> the [Collect]
 * chest -> "Collect Items"}, then shift-click each stack out.
 *
 * <p>An order is a resting bid: it fills at <em>our</em> price when sellers deliver into it,
 * instead of us paying whatever the cheapest ask happens to be. That is why it is worth the
 * navigation. The price is per item, which is the unit the rest of this mod now works in.
 */
public final class OrderFlow {
    /** Titles are the state machine's only trustworthy signal, so they are matched exactly. */
    private static final String ORDERS_ROOT = "orders (page";
    private static final String YOUR_ORDERS = "orders -> your orders";
    private static final String EDIT_ORDER = "orders -> edit order";
    private static final String COLLECT_ITEMS = "orders -> collect items";
    private static final String CHOOSE_ITEM = "choose item";
    private static final String HOW_MANY = "how many?";
    private static final String PRICE_PER_ITEM = "price per item?";
    private static final String REVIEW_ORDER = "review order";

    /** The "Your Orders" slot on the orders root screen, from the recording. */
    private static final int YOUR_ORDERS_SLOT = 51;

    private static final Pattern REVIEW_AMOUNT = Pattern.compile("(?i)amount:\\s*([0-9][0-9,.]*)\\s*([kmb]?)");
    private static final Pattern REVIEW_PRICE = Pattern.compile("(?i)price:\\s*\\$\\s*([0-9][0-9,.]*)\\s*([kmb]?)");

    public enum Step {
        IDLE, OPEN_ORDERS, YOUR_ORDERS_SCREEN, NEW_ORDER, CHOOSE_ITEM_SEARCH, PICK_ITEM,
        ENTER_AMOUNT, ENTER_PRICE, REVIEW, DONE, FAILED,
        COLLECT_OPEN, COLLECT_YOUR_ORDERS, COLLECT_EDIT, COLLECT_CHEST, COLLECT_TAKE
    }

    /** What the review screen actually says, parsed back out for verification. */
    public record Review(long amount, long pricePerItem) {}

    private static Step step = Step.IDLE;
    private static String itemId = "";
    private static String itemName = "";
    private static long amount;
    private static long pricePerItem;
    private static long stepAtMs;
    private static String note = "idle";
    private static int typed;
    private static int emptyScans;
    private static int taken;

    /** Consecutive empty reads of the collect chest required before believing it is empty. */
    private static final int EMPTY_SCANS_BEFORE_DONE = 6;

    private OrderFlow() {}

    public static boolean busy() {
        return step != Step.IDLE && step != Step.DONE && step != Step.FAILED;
    }

    /** The per-item price of the most recent order, which is the basis of what it delivers. */
    public static long lastOrderUnitPrice() {
        return pricePerItem;
    }

    public static Step step() {
        return step;
    }

    public static String note() {
        return note;
    }

    /** Starts placing one order. Price is per item, matching the server's own dialog. */
    public static String place(String deskItemId, String deskItemName, long orderAmount, long unitPrice) {
        if (busy()) return "already running: " + step + " (" + note + ")";
        if (orderAmount <= 0 || unitPrice <= 0) return "amount and price per item must both be positive";
        itemId = deskItemId;
        itemName = deskItemName;
        amount = orderAmount;
        pricePerItem = unitPrice;
        typed = 0;
        advance(Step.OPEN_ORDERS, "placing an order for " + amount + "x " + itemName
                + " at " + unitPrice + "/item (total " + amount * unitPrice + ")");
        return note;
    }

    /** Starts collecting whatever a completed order has delivered, from scratch via /orders. */
    public static String collect(String deskItemId) {
        if (busy()) return "already running: " + step + " (" + note + ")";
        itemId = deskItemId;
        emptyScans = 0;
        taken = 0;
        advance(Step.COLLECT_OPEN, "collecting delivered " + deskItemId);
        return note;
    }

    /**
     * Resumes collection from the screen that is already open.
     *
     * <p>Navigation is the fragile part of this flow, so when you have already walked to the right
     * screen there is no reason to make the machine walk there again - and no way to tell it to,
     * since chat is unreachable with a container open. The open screen's title says which step we
     * are on, so this just starts there.
     */
    public static String collectFrom(Minecraft client, String deskItemId) {
        Screen screen = client.gui.screen();
        String title = screen == null || screen.getTitle() == null ? ""
                : screen.getTitle().getString().toLowerCase(Locale.ROOT);
        itemId = deskItemId;
        emptyScans = 0;
        taken = 0;
        if (title.contains(COLLECT_ITEMS)) {
            advance(Step.COLLECT_TAKE, "resuming on the collect screen");
        } else if (title.contains(EDIT_ORDER)) {
            advance(Step.COLLECT_CHEST, "resuming on the edit screen");
        } else if (title.contains(YOUR_ORDERS)) {
            advance(Step.COLLECT_EDIT, "resuming on your orders");
        } else if (title.contains(ORDERS_ROOT)) {
            advance(Step.COLLECT_YOUR_ORDERS, "resuming on the orders root");
        } else {
            advance(Step.COLLECT_OPEN, "no order screen open; walking there");
        }
        return note;
    }

    /** Called from the engine tick while this flow owns the GUI. Returns true if it acted. */
    public static boolean tick(Minecraft client, long nowMs) {
        if (!busy()) return false;
        LocalPlayer player = client.player;
        if (player == null) { advance(Step.FAILED, "no player"); return false; }
        if (nowMs - stepAtMs > 8_000L) {
            advance(Step.FAILED, "timed out in " + step);
            return false;
        }
        Screen screen = client.gui.screen();
        String title = screen == null || screen.getTitle() == null ? ""
                : screen.getTitle().getString().toLowerCase(Locale.ROOT);

        return switch (step) {
            case OPEN_ORDERS, COLLECT_OPEN -> {
                if (title.contains(ORDERS_ROOT)) {
                    advance(step == Step.OPEN_ORDERS ? Step.YOUR_ORDERS_SCREEN : Step.COLLECT_YOUR_ORDERS,
                            "orders root open");
                    yield true;
                }
                if (screen != null) { closeScreen(player, screen); yield true; }
                yield sendCommand(client, "orders", nowMs);
            }
            case YOUR_ORDERS_SCREEN, COLLECT_YOUR_ORDERS -> {
                if (title.contains(YOUR_ORDERS)) {
                    advance(step == Step.YOUR_ORDERS_SCREEN ? Step.NEW_ORDER : Step.COLLECT_EDIT,
                            "your orders open");
                    yield true;
                }
                if (!title.contains(ORDERS_ROOT) || !(screen instanceof AbstractContainerScreen<?> c)) yield false;
                yield click(client, player, c, YOUR_ORDERS_SLOT, ContainerInput.PICKUP, nowMs);
            }
            case NEW_ORDER -> {
                if (title.startsWith(CHOOSE_ITEM)) { advance(Step.CHOOSE_ITEM_SEARCH, "choose item open"); yield true; }
                if (!(screen instanceof AbstractContainerScreen<?> c) || !title.contains(YOUR_ORDERS)) yield false;
                int slot = findSlotNamed(client, c, "new order");
                if (slot < 0) { advance(Step.FAILED, "no free [New Order] slot"); yield false; }
                yield click(client, player, c, slot, ContainerInput.PICKUP, nowMs);
            }
            case CHOOSE_ITEM_SEARCH -> {
                if (!title.startsWith(CHOOSE_ITEM)) yield false;
                // The results title carries a count: "Choose Item (2 results)".
                if (title.contains("result") && findItemButton(screen, itemName) != null) {
                    advance(Step.PICK_ITEM, "search returned results");
                    yield true;
                }
                if (!setInput(screen, itemName)) yield false;
                yield press(screen, "search", nowMs);
            }
            case PICK_ITEM -> {
                if (title.startsWith(HOW_MANY)) { advance(Step.ENTER_AMOUNT, "amount prompt open"); yield true; }
                AbstractButton button = findItemButton(screen, itemName);
                if (button == null) yield false;
                yield pressButton(button, nowMs, "picked " + itemName);
            }
            case ENTER_AMOUNT -> {
                if (title.startsWith(PRICE_PER_ITEM)) { advance(Step.ENTER_PRICE, "price prompt open"); yield true; }
                if (!title.startsWith(HOW_MANY)) yield false;
                if (!setInput(screen, Long.toString(amount))) yield false;
                yield press(screen, "next", nowMs);
            }
            case ENTER_PRICE -> {
                if (title.startsWith(REVIEW_ORDER)) { advance(Step.REVIEW, "review open"); yield true; }
                if (!title.startsWith(PRICE_PER_ITEM)) yield false;
                if (!setInput(screen, Long.toString(pricePerItem))) yield false;
                yield press(screen, "review order", nowMs);
            }
            case REVIEW -> {
                if (!title.startsWith(REVIEW_ORDER)) yield false;
                Review review = parseReview(dialogTexts(screen));
                if (review == null) yield false;
                if (!matches(review, amount, pricePerItem)) {
                    advance(Step.FAILED, "review says " + review.amount + " at " + review.pricePerItem
                            + "/item but we asked for " + amount + " at " + pricePerItem);
                    closeScreen(player, screen);
                    yield false;
                }
                if (!press(screen, "create order", nowMs)) yield false;
                advance(Step.DONE, "order created: " + amount + "x " + itemName + " at "
                        + pricePerItem + "/item");
                yield true;
            }
            case COLLECT_EDIT -> {
                if (title.contains(EDIT_ORDER)) { advance(Step.COLLECT_CHEST, "edit order open"); yield true; }
                if (!(screen instanceof AbstractContainerScreen<?> c) || !title.contains(YOUR_ORDERS)) yield false;
                int slot = findOrderSlot(client, c);
                if (slot < 0) {
                    // Dump the screen we could not read, so the next fix is based on evidence
                    // rather than another guess about what an order tile looks like.
                    MarketFeed.dumpOpenScreen(client, msg -> {});
                    advance(Step.FAILED, "no live order for " + itemId
                            + " on the Your Orders screen; dumped it to screen_dumps.txt");
                    yield false;
                }
                yield click(client, player, c, slot, ContainerInput.PICKUP, nowMs);
            }
            case COLLECT_CHEST -> {
                if (title.contains(COLLECT_ITEMS)) { advance(Step.COLLECT_TAKE, "collect screen open"); yield true; }
                if (!(screen instanceof AbstractContainerScreen<?> c) || !title.contains(EDIT_ORDER)) yield false;
                int slot = findSlotNamed(client, c, "collect");
                if (slot < 0) { advance(Step.FAILED, "no [Collect] chest on the edit screen"); yield false; }
                yield click(client, player, c, slot, ContainerInput.PICKUP, nowMs);
            }
            case COLLECT_TAKE -> {
                if (!(screen instanceof AbstractContainerScreen<?> c) || !title.contains(COLLECT_ITEMS)) yield false;
                int slot = findDeskItemSlot(c);
                if (slot < 0) {
                    // One empty read is not an empty chest. The server sends the collect screen
                    // before it fills, and it restates the whole container after every shift-click,
                    // so a single blank frame mid-update used to end the flow with stacks still in
                    // there - which is exactly the way this failed. Require several in a row.
                    if (++emptyScans < EMPTY_SCANS_BEFORE_DONE) yield false;
                    if (taken == 0) {
                        advance(Step.FAILED, "collect screen never showed any " + itemId
                                + " - shift-click them out by hand and tell me what it looked like");
                        yield false;
                    }
                    closeScreen(player, screen);
                    advance(Step.DONE, "collected " + taken + " stacks");
                    yield true;
                }
                emptyScans = 0;
                // Shift-click, exactly as recorded: QUICK_MOVE with button 1.
                if (!click(client, player, c, slot, ContainerInput.QUICK_MOVE, nowMs)) yield false;
                taken++;
                yield true;
            }
            default -> false;
        };
    }

    // ---- pure helpers, testable ------------------------------------------------------------

    /**
     * True when a "Choose Item" button is the exact item, not a relative of it. The recorded
     * labels look like {@code "[block/obsidian] Obsidian"}, and searching "obsidian" also returns
     * {@code "Crying Obsidian"} - so the name after the bracket has to match exactly.
     */
    static boolean matchesItemButton(String label, String wantedName) {
        if (label == null || wantedName == null) return false;
        String text = label.trim();
        int close = text.indexOf(']');
        if (close >= 0 && close + 1 < text.length()) text = text.substring(close + 1);
        return text.trim().equalsIgnoreCase(wantedName.trim());
    }

    /** Reads the amount and per-item price back off the review dialog. */
    static Review parseReview(List<String> texts) {
        long amount = -1;
        long price = -1;
        for (String text : texts) {
            if (text == null) continue;
            Matcher a = REVIEW_AMOUNT.matcher(text);
            if (amount < 0 && a.find()) amount = Math.round(MarketFeed.compactNumber(a.group(1), a.group(2)));
            Matcher p = REVIEW_PRICE.matcher(text);
            if (price < 0 && p.find()) price = Math.round(MarketFeed.compactNumber(p.group(1), p.group(2)));
        }
        return amount > 0 && price > 0 ? new Review(amount, price) : null;
    }

    /**
     * The review screen rounds what it displays ("2.5K" for 2,560), so the comparison allows the
     * rounding but nothing more: the price has to be exact, and the amount within a percent.
     */
    static boolean matches(Review review, long wantAmount, long wantPrice) {
        if (review == null) return false;
        if (review.pricePerItem != wantPrice) return false;
        double slack = Math.max(1.0, wantAmount * 0.05);
        return Math.abs(review.amount - wantAmount) <= slack;
    }

    // ---- screen helpers --------------------------------------------------------------------

    private static void advance(Step next, String why) {
        step = next;
        note = why;
        stepAtMs = System.currentTimeMillis();
        typed = 0;
        MarketFeed.log("order", next + ": " + why);
    }

    private static boolean sendCommand(Minecraft client, String command, long nowMs) {
        if (client.getConnection() == null) return false;
        if (!ActionPacer.charge(nowMs, 1.0)) return false;
        client.getConnection().sendCommand(command);
        stepAtMs = nowMs;
        return true;
    }

    private static boolean click(Minecraft client, LocalPlayer player, AbstractContainerScreen<?> screen,
                                 int slot, ContainerInput input, long nowMs) {
        if (client.gameMode == null || slot < 0) return false;
        if (!ActionPacer.charge(nowMs, 1.0)) return false;
        int button = input == ContainerInput.QUICK_MOVE ? 1 : 0;
        client.gameMode.handleContainerInput(screen.getMenu().containerId, slot, button, input, player);
        stepAtMs = nowMs;
        return true;
    }

    private static boolean press(Screen screen, String label, long nowMs) {
        AbstractButton button = findButton(screen == null ? List.of() : screen.children(), label);
        if (button == null) return false;
        return pressButton(button, nowMs, "pressed " + label);
    }

    private static boolean pressButton(AbstractButton button, long nowMs, String why) {
        if (!ActionPacer.charge(nowMs, 1.0)) return false;
        try {
            button.onPress(null);
        } catch (Throwable ignored) {
            return false;
        }
        stepAtMs = nowMs;
        MarketFeed.log("order", why);
        return true;
    }

    /** Types into the dialog's text field; the server only reads it when a button is pressed. */
    private static boolean setInput(Screen screen, String value) {
        EditBox box = findEditBox(screen == null ? List.of() : screen.children());
        if (box == null) return false;
        if (!value.equals(box.getValue())) {
            box.setFocused(true);
            box.setValue(value);
            typed++;
            return false;   // let one tick pass so the widget settles before pressing
        }
        return true;
    }

    private static EditBox findEditBox(List<? extends GuiEventListener> elements) {
        for (GuiEventListener element : elements) {
            if (element instanceof EditBox box && box.isVisible()) return box;
            if (element instanceof ContainerEventHandler parent) {
                EditBox nested = findEditBox(parent.children());
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static AbstractButton findButton(List<? extends GuiEventListener> elements, String label) {
        for (GuiEventListener element : elements) {
            if (element instanceof AbstractButton button
                    && button.getMessage().getString().trim().equalsIgnoreCase(label)) {
                return button;
            }
            if (element instanceof ContainerEventHandler parent) {
                AbstractButton nested = findButton(parent.children(), label);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static AbstractButton findItemButton(Screen screen, String wantedName) {
        return findItemButton(screen == null ? List.of() : screen.children(), wantedName);
    }

    private static AbstractButton findItemButton(List<? extends GuiEventListener> elements, String wantedName) {
        for (GuiEventListener element : elements) {
            if (element instanceof AbstractButton button
                    && matchesItemButton(button.getMessage().getString(), wantedName)) {
                return button;
            }
            if (element instanceof ContainerEventHandler parent) {
                AbstractButton nested = findItemButton(parent.children(), wantedName);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static List<String> dialogTexts(Screen screen) {
        List<String> out = new ArrayList<>();
        if (!(screen instanceof DialogScreen<?> dialogScreen)) return out;
        try {
            Dialog dialog = ((DialogScreenAccessor) (Object) dialogScreen).echest$getDialog();
            if (dialog != null && dialog.common() != null) {
                for (DialogBody body : dialog.common().body()) {
                    if (body instanceof PlainMessage plain) out.add(plain.contents().getString());
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static int findSlotNamed(Minecraft client, AbstractContainerScreen<?> screen, String needle) {
        AbstractContainerMenu menu = screen.getMenu();
        List<ItemStack> stacks = menu.getItems();
        int top = Math.max(0, stacks.size() - MarketFeed.PLAYER_INVENTORY_SLOTS);
        if (top == 0) top = stacks.size();
        for (int i = 0; i < top; i++) {
            ItemStack stack = stacks.get(i);
            if (stack == null || stack.isEmpty()) continue;
            String name = stack.getDisplayName().getString().toLowerCase(Locale.ROOT);
            if (name.contains(needle)) return i;
        }
        return -1;
    }

    /**
     * Our live order on the Your Orders screen.
     *
     * <p>Matched by tooltip rather than by item, because the walkthrough recording contained no
     * live order to learn the tile from - every slot was a {@code [New Order]} pane - and the
     * icon guess was wrong in practice. What is known, from the Edit Order screen, is the wording
     * on an order's tooltip: {@code "2.5K requested / $391 each / 2.5K/2.5K Delivered"}. That is
     * the evidence, so that is what this reads.
     */
    private static int findOrderSlot(Minecraft client, AbstractContainerScreen<?> screen) {
        AbstractContainerMenu menu = screen.getMenu();
        List<ItemStack> stacks = menu.getItems();
        int top = Math.max(0, stacks.size() - MarketFeed.PLAYER_INVENTORY_SLOTS);
        if (top == 0) top = stacks.size();
        int fallback = -1;
        for (int i = 0; i < top; i++) {
            ItemStack stack = stacks.get(i);
            if (stack == null || stack.isEmpty()) continue;
            String name = stack.getDisplayName().getString().toLowerCase(Locale.ROOT);
            if (name.contains("new order")) continue;              // a free slot, not an order
            String tip = MarketFeed.joinLines(MarketFeed.tooltipLines(client, stack), " | ").toLowerCase(Locale.ROOT);
            boolean isOrder = tip.contains("delivered") || tip.contains("requested");
            if (!isOrder) continue;
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            boolean ours = id != null && itemId.equals(id.toString());
            if (ours || tip.contains(itemName.toLowerCase(Locale.ROOT))) return i;
            if (fallback < 0) fallback = i;
        }
        return fallback;
    }


    private static int findDeskItemSlot(AbstractContainerScreen<?> screen) {
        AbstractContainerMenu menu = screen.getMenu();
        List<ItemStack> stacks = menu.getItems();
        int top = Math.max(0, stacks.size() - MarketFeed.PLAYER_INVENTORY_SLOTS);
        if (top == 0) top = stacks.size();
        for (int i = 0; i < top; i++) {
            ItemStack stack = stacks.get(i);
            if (stack == null || stack.isEmpty()) continue;
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null && itemId.equals(id.toString())) return i;
        }
        return -1;
    }

    private static void closeScreen(LocalPlayer player, Screen screen) {
        try {
            player.closeContainer();
        } catch (Throwable ignored) {
        }
    }

    /** Chat confirmation, forwarded by the engine: "You ordered 2.5K Obsidian". */
    public static void noteChat(String text) {
        if (text == null) return;
        String lower = text.toLowerCase(Locale.ROOT);
        if (step == Step.REVIEW || step == Step.DONE) {
            if (lower.contains("you ordered")) advance(Step.DONE, "server confirmed: " + text);
        }
        if (busy() && (lower.contains("not enough money") || lower.contains("can't afford"))) {
            advance(Step.FAILED, "server refused the order: " + text);
        }
    }

    static Component describe() {
        return Component.literal("[EchestMM] order flow " + step + ": " + note);
    }
}
