package net.minto.hoppingelytra.mixin;

import net.minto.hoppingelytra.HoppingElytraClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onEntityTrackerUpdate", at = @At("TAIL"))
    private void hoppingElytra$observeLocalPlayerGliding(
            EntityTrackerUpdateS2CPacket packet,
            CallbackInfo callbackInfo
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || packet.id() != client.player.getId()) {
            return;
        }

        int flagsId = EntityFlagsAccessor.hoppingElytra$getFlags().id();
        boolean containsEntityFlags = packet.trackedValues().stream()
                .anyMatch(entry -> entry.id() == flagsId);
        if (!containsEntityFlags) {
            return;
        }

        // TAILで読むことで、通常のDataTracker適用後のサーバー権威値だけを通知する。
        HoppingElytraClient.onServerGlidingState(client.player.isGliding());
    }
}
