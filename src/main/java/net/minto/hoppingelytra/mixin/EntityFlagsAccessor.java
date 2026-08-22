package net.minto.hoppingelytra.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.data.TrackedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface EntityFlagsAccessor {
    /**
     * FLAGSの実際のTrackedData IDを取得し、数値IDのハードコードを避ける。
     */
    @Accessor("FLAGS")
    static TrackedData<Byte> hoppingElytra$getFlags() {
        throw new AssertionError();
    }
}
