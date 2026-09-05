package dev.parker.echest;

import org.junit.jupiter.api.Test;

import static dev.parker.echest.MarketFeed.SlotKind;
import static dev.parker.echest.MarketFeed.classifyOwnSlot;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Slot classification for the Your Items screen.
 *
 * <p>The first version scanned a fixed 18 slots and counted anything that was not a pane as a
 * taken slot. On an account with more listing rows that read "18 taken, 0 free" while two whole
 * rows sat free below the window, and the engine announced the auction house was full. Nothing
 * here may depend on slot position or row count.
 */
final class OwnListingsScanTest {
    @Test
    void listPanesAreFreeSlotsWhateverTheirColour() {
        assertEquals(SlotKind.FREE, classifyOwnSlot("minecraft:gray_stained_glass_pane", "List", false));
        assertEquals(SlotKind.FREE, classifyOwnSlot("minecraft:black_stained_glass_pane", "Click to sell an item", false));
        assertEquals(SlotKind.FREE, classifyOwnSlot("minecraft:light_gray_stained_glass_pane", "Empty Slot", false));
        assertEquals(SlotKind.FREE, classifyOwnSlot("minecraft:glass_pane", "Available", false));
    }

    @Test
    void redPanesAndUnlockPromptsAreLocked() {
        assertEquals(SlotKind.LOCKED, classifyOwnSlot("minecraft:red_stained_glass_pane", "Locked", false));
        assertEquals(SlotKind.LOCKED, classifyOwnSlot("minecraft:iron_bars", "Unlock for $1M", false));
        // Locked wins over a listing-like name: it is never a place we can list into.
        assertEquals(SlotKind.LOCKED, classifyOwnSlot("minecraft:red_stained_glass_pane", "List slot locked", false));
    }

    @Test
    void pricedItemsAreActiveListings() {
        assertEquals(SlotKind.LISTING, classifyOwnSlot("minecraft:ender_chest", "Ender Chest", true));
        assertEquals(SlotKind.LISTING, classifyOwnSlot("minecraft:obsidian", "Obsidian", true));
    }

    @Test
    void decorationAndControlsAreIgnoredRatherThanCountedAsTaken() {
        assertEquals(SlotKind.OTHER, classifyOwnSlot("minecraft:arrow", "Next Page", false));
        assertEquals(SlotKind.OTHER, classifyOwnSlot("minecraft:barrier", "Close", false));
        assertEquals(SlotKind.OTHER, classifyOwnSlot("minecraft:player_head", "Your Profile", false));
        // A plain filler pane with no listing wording is decoration, not a free slot.
        assertEquals(SlotKind.OTHER, classifyOwnSlot("minecraft:blue_stained_glass_pane", " ", false));
    }

    @Test
    void unknownIdsFallBackToWhetherAPriceIsShown() {
        assertEquals(SlotKind.LISTING, classifyOwnSlot("", "Something", true));
        assertEquals(SlotKind.OTHER, classifyOwnSlot("", "Something", false));
        assertEquals(SlotKind.OTHER, classifyOwnSlot(null, null, false));
    }
}
