package dev.parker.echest;

import dev.parker.echest.mixin.DialogScreenAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
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
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Records a GUI flow verbatim so an automation can be written from evidence instead of guesswork.
 *
 * <p>The buy-order interface is a multi-step server-driven flow, and the earlier attempt at
 * automating a flow like it was written blind - it opened the item catalogue, looked for prices
 * that were one screen deeper, found none, and gave up. Rather than guess again: arm this, walk
 * the flow by hand once, and every screen, slot, tooltip, dialog body, button label, text field
 * and click lands in a file with container ids and state ids attached.
 *
 * <p>Strictly read-only. It sends nothing, clicks nothing, and cancels nothing.
 *
 * <p>Usage: {@code /echest record on}, do the thing by hand, {@code /echest record off}. Files land
 * in {@code .minecraft/echestmm/flow/}.
 */
public final class FlowRecorder {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(java.time.ZoneId.systemDefault());
    /** Re-dump a screen when the server changes it, but no faster than this. */
    private static final long RESCAN_THROTTLE_MS = 200L;

    private static boolean recording;
    private static Path file;
    private static Screen lastScreen;
    private static int lastStateId = Integer.MIN_VALUE;
    private static long lastDumpMs;
    private static int step;

    private FlowRecorder() {}

    static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (recording) dump(client, screen, "SCREEN OPEN");
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (recording && message != null) write("CHAT  \"" + message.getString() + "\"");
        });
        ClientTickEvents.END_CLIENT_TICK.register(FlowRecorder::tick);
    }

    public static boolean isRecording() {
        return recording;
    }

    /** Starts a new recording file. Returns a message for the player. */
    public static String start() {
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("echestmm").resolve("flow");
            Files.createDirectories(dir);
            String name = "flow-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .withZone(java.time.ZoneId.systemDefault()).format(Instant.now()) + ".txt";
            file = dir.resolve(name);
            recording = true;
            step = 0;
            lastScreen = null;
            lastStateId = Integer.MIN_VALUE;
            write("# EchestMM flow recording started " + Instant.now());
            write("# every screen, slot, tooltip, dialog, button, text field and click follows");
            Minecraft client = Minecraft.getInstance();
            if (client.gui.screen() != null) dump(client, client.gui.screen(), "SCREEN ALREADY OPEN");
            return "recording to echestmm/flow/" + name + " - walk the flow by hand, then /echest record off";
        } catch (IOException e) {
            recording = false;
            return "could not start recording: " + e;
        }
    }

    public static String stop() {
        if (!recording) return "not recording";
        write("# recording stopped " + Instant.now() + " after " + step + " steps");
        recording = false;
        return "recording stopped after " + step + " steps: " + (file == null ? "?" : file.getFileName());
    }

    /** Annotates the recording, so a walkthrough can be narrated as it happens. */
    public static String mark(String text) {
        if (!recording) return "not recording";
        write("MARK  " + text);
        return "marked: " + text;
    }

    /** Called from the container-screen mixin: the slot the player actually clicked. */
    public static void noteSlotClick(AbstractContainerScreen<?> screen, Slot slot, int slotId,
                                     int button, ContainerInput input) {
        if (!recording) return;
        AbstractContainerMenu menu = screen.getMenu();
        ItemStack stack = slot == null ? ItemStack.EMPTY : slot.getItem();
        write("CLICK slot=" + slotId + " button=" + button + " input=" + input
                + " containerId=" + menu.containerId + " stateId=" + menu.getStateId()
                + " item=" + describe(Minecraft.getInstance(), stack));
    }

    private static void tick(Minecraft client) {
        if (!recording) return;
        Screen screen = client.gui.screen();
        if (screen == null) {
            if (lastScreen != null) {
                write("SCREEN CLOSED");
                lastScreen = null;
                lastStateId = Integer.MIN_VALUE;
            }
            return;
        }
        if (screen != lastScreen) {
            dump(client, screen, "SCREEN CHANGED");
            return;
        }
        // The server mutating an open screen is exactly what a blind automation misses.
        if (screen instanceof AbstractContainerScreen<?> container) {
            int stateId = container.getMenu().getStateId();
            long now = System.currentTimeMillis();
            if (stateId != lastStateId && now - lastDumpMs >= RESCAN_THROTTLE_MS) {
                dump(client, screen, "SCREEN UPDATED BY SERVER");
            }
        }
    }

    private static void dump(Minecraft client, Screen screen, String why) {
        if (screen == null) return;
        lastScreen = screen;
        lastDumpMs = System.currentTimeMillis();
        step++;

        StringBuilder out = new StringBuilder(2048);
        out.append("=== step ").append(step).append(' ').append(why)
                .append(" class=").append(screen.getClass().getSimpleName())
                .append(" title=\"").append(screen.getTitle() == null ? "" : screen.getTitle().getString())
                .append('"');

        if (screen instanceof AbstractContainerScreen<?> container) {
            AbstractContainerMenu menu = container.getMenu();
            lastStateId = menu.getStateId();
            List<ItemStack> stacks = menu.getItems();
            int top = Math.max(0, stacks.size() - MarketFeed.PLAYER_INVENTORY_SLOTS);
            if (top == 0) top = stacks.size();
            out.append(" containerId=").append(menu.containerId)
                    .append(" stateId=").append(menu.getStateId())
                    .append(" slots=").append(stacks.size())
                    .append(" topSlots=").append(top).append('\n');
            for (int i = 0; i < top; i++) {
                ItemStack stack = stacks.get(i);
                if (stack == null || stack.isEmpty()) continue;
                out.append("  slot ").append(i).append(": ").append(describe(client, stack)).append('\n');
            }
        } else {
            out.append('\n');
        }

        if (screen instanceof DialogScreen<?> dialogScreen) {
            out.append("  dialog:\n");
            try {
                Dialog dialog = ((DialogScreenAccessor) (Object) dialogScreen).echest$getDialog();
                if (dialog != null && dialog.common() != null) {
                    if (dialog.common().title() != null) {
                        out.append("    title: ").append(dialog.common().title().getString()).append('\n');
                    }
                    for (DialogBody body : dialog.common().body()) {
                        if (body instanceof PlainMessage plain) {
                            out.append("    text: ").append(plain.contents().getString()).append('\n');
                        } else if (body instanceof ItemBody itemBody) {
                            itemBody.description().ifPresent(d ->
                                    out.append("    itemText: ").append(d.contents().getString()).append('\n'));
                            try {
                                ItemStack stack = itemBody.item().create();
                                out.append("    item: ").append(describe(client, stack)).append('\n');
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                out.append("    (dialog body unavailable: ").append(t).append(")\n");
            }
        }

        appendWidgets(screen.children(), out, "  ");
        write(out.toString().stripTrailing());
    }

    private static void appendWidgets(List<? extends GuiEventListener> elements, StringBuilder out, String indent) {
        for (GuiEventListener element : elements) {
            if (element instanceof AbstractButton button) {
                out.append(indent).append("button: \"").append(button.getMessage().getString())
                        .append("\" at ").append(button.getX()).append(',').append(button.getY())
                        .append(' ').append(button.getWidth()).append('x').append(button.getHeight()).append('\n');
            } else if (element instanceof EditBox box) {
                out.append(indent).append("editbox: value=\"").append(box.getValue())
                        .append("\" hint=\"").append(box.getMessage() == null ? "" : box.getMessage().getString())
                        .append("\" visible=").append(box.isVisible()).append('\n');
            }
            if (element instanceof ContainerEventHandler parent) {
                appendWidgets(parent.children(), out, indent + "  ");
            }
        }
    }

    private static String describe(Minecraft client, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "(empty)";
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        StringBuilder b = new StringBuilder();
        b.append(stack.getCount()).append("x ").append(id == null ? "?" : id.toString())
                .append(" '").append(stack.getDisplayName().getString()).append('\'');
        try {
            List<Component> tip = Screen.getTooltipFromItem(client, stack);
            if (tip != null && !tip.isEmpty()) {
                b.append(" | ");
                for (int i = 0; i < tip.size(); i++) {
                    String line = tip.get(i).getString();
                    if (line.isBlank()) continue;
                    if (i > 0) b.append(" / ");
                    b.append(line);
                }
            }
        } catch (Exception ignored) {
        }
        return b.toString();
    }

    private static void write(String block) {
        if (file == null) return;
        String line = String.format(Locale.ROOT, "%s %s%n", STAMP.format(Instant.now()), block);
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }
}
