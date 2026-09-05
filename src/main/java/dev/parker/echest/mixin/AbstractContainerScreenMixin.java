package dev.parker.echest.mixin;

import dev.parker.echest.FlowRecorder;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records the slot the player actually clicked, which is the one thing a screen dump cannot show.
 * Read-only: it observes, never redirects. {@code require = 0} keeps the mod bootable if Mojang
 * renames the method.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
    @Inject(method = "slotClicked", at = @At("TAIL"), require = 0)
    private void echest$afterSlotClicked(Slot slot, int slotId, int button, ContainerInput input,
                                         CallbackInfo ci) {
        FlowRecorder.noteSlotClick((AbstractContainerScreen<?>) (Object) this, slot, slotId, button, input);
    }
}
