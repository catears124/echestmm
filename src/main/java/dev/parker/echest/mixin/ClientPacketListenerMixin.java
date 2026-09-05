package dev.parker.echest.mixin;

import dev.parker.echest.MarketFeed;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Event-path accelerator. Vanilla has already applied the inbound container mutation when these
 * TAIL hooks run, so the market feed can read the synchronized menu immediately instead of waiting
 * for a render frame or the next 20 Hz tick. {@code require = 0} keeps the mod bootable if Mojang
 * renames a handler; the tick path remains as the fallback.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleContainerContent", at = @At("TAIL"), require = 0)
    private void echest$afterContainerContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        MarketFeed.onInboundContainerPacket();
    }

    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"), require = 0)
    private void echest$afterContainerSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        MarketFeed.onInboundContainerPacket();
    }

    @Inject(method = "handleSetPlayerInventory", at = @At("TAIL"), require = 0)
    private void echest$afterPlayerInventory(ClientboundSetPlayerInventoryPacket packet, CallbackInfo ci) {
        MarketFeed.onInboundContainerPacket();
    }
}
