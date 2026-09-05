package dev.parker.echest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The buy-order flow's two risky decisions, both taken from a recorded walkthrough.
 *
 * <p>Picking the item: searching "obsidian" returns {@code "[block/crying_obsidian] Crying
 * Obsidian"} alongside {@code "[block/obsidian] Obsidian"}, so a substring match would order the
 * wrong block. Confirming the order: the review screen is the last point before money moves, and
 * it rounds what it displays - "Amount: 2560" appeared as "2.5K" in chat - so the check has to
 * tolerate the rounding on quantity while insisting the price is exact.
 */
final class OrderFlowTest {
    @Test
    void picksTheExactItemAndNotItsRelatives() {
        assertTrue(OrderFlow.matchesItemButton("[block/obsidian] Obsidian", "Obsidian"));
        assertFalse(OrderFlow.matchesItemButton("[block/crying_obsidian] Crying Obsidian", "Obsidian"));
        assertTrue(OrderFlow.matchesItemButton("[block/crying_obsidian] Crying Obsidian", "Crying Obsidian"));
        assertTrue(OrderFlow.matchesItemButton("[item/ender_chest@items] Ender Chest", "Ender Chest"));
        assertFalse(OrderFlow.matchesItemButton("[block/chest] Chest", "Ender Chest"));
    }

    @Test
    void toleratesLabelsWithoutAnIconPrefix() {
        assertTrue(OrderFlow.matchesItemButton("Obsidian", "obsidian"));
        assertFalse(OrderFlow.matchesItemButton("", "Obsidian"));
        assertFalse(OrderFlow.matchesItemButton(null, "Obsidian"));
    }

    @Test
    void readsTheReviewScreenBack() {
        OrderFlow.Review review = OrderFlow.parseReview(List.of(
                "Item: Obsidian", "Amount: 2560", "Price: $ 391 each", "Total: $ 1M"));
        assertEquals(2560, review.amount());
        assertEquals(391, review.pricePerItem());
    }

    @Test
    void readsCompactQuantitiesAndPrices() {
        OrderFlow.Review review = OrderFlow.parseReview(List.of(
                "Amount: 2.5K", "Price: $ 1.2K each"));
        assertEquals(2_500, review.amount());
        assertEquals(1_200, review.pricePerItem());
    }

    @Test
    void refusesAnUnreadableReview() {
        assertNull(OrderFlow.parseReview(List.of("Item: Obsidian")));
        assertNull(OrderFlow.parseReview(List.of()));
    }

    @Test
    void acceptsTheOrderOnlyWhenTheReviewAgrees() {
        OrderFlow.Review review = new OrderFlow.Review(2560, 391);
        assertTrue(OrderFlow.matches(review, 2560, 391));
        // Displayed quantity rounds; a few items either way is the server's own rendering.
        assertTrue(OrderFlow.matches(new OrderFlow.Review(2500, 391), 2560, 391));
        // Price must be exact: this is the last gate before money moves.
        assertFalse(OrderFlow.matches(review, 2560, 390));
        assertFalse(OrderFlow.matches(review, 2560, 3910));
        // A wildly different quantity is a different order.
        assertFalse(OrderFlow.matches(new OrderFlow.Review(25600, 391), 2560, 391));
        assertFalse(OrderFlow.matches(null, 2560, 391));
    }
}
