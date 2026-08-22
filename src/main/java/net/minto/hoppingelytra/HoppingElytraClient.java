package net.minto.hoppingelytra;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HoppingElytraClient implements ClientModInitializer {
    public static final String MOD_ID = "hopping-elytra";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static HoppingElytraClient instance;

    private final RemoteGlidingRecovery remoteRecovery = new RemoteGlidingRecovery();
    private ClientPlayerEntity trackedPlayer;
    private ClientWorld trackedWorld;
    private boolean prevOnGround = true;

    @Override
    public void onInitializeClient() {
        instance = this;
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetAllTracking());
        LOGGER.info("HoppingElytra initialized");
    }

    /**
     * ClientPlayNetworkHandlerのMixinから、サーバーが同期した本人の滑空状態だけを受け取る。
     */
    public static void onServerGlidingState(boolean gliding) {
        if (instance != null) {
            instance.handleServerGlidingState(gliding);
        }
    }

    private void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null || client.getNetworkHandler() == null) {
            resetAllTracking();
            return;
        }

        // リスポーンやワールド切替後に、直前の接地状態を新しいプレイヤーへ持ち越さない。
        if (trackedPlayer != player || trackedWorld != world) {
            remoteRecovery.reset();
            trackedPlayer = player;
            trackedWorld = world;
            prevOnGround = player.isOnGround();
            return;
        }

        boolean onGround = player.isOnGround();
        boolean airborne = !onGround;
        boolean jumpHeld = player.input.playerInput.jump();
        boolean remoteServer = shouldUseRemoteRecovery(client.isIntegratedServerRunning());
        boolean continuationValid = canContinueHopping(client, player, jumpHeld);

        if (!remoteServer) {
            // 統合サーバーでは応答待ちと再試行を使わず、従来どおり一度だけ送信する。
            remoteRecovery.reset();
        } else if (remoteRecovery.isActive()) {
            runRecoveryAction(client, remoteRecovery.tick(airborne, continuationValid));
        }

        boolean hopTakeoff = isHopTakeoff(
                jumpHeld,
                prevOnGround,
                airborne,
                player.isGliding(),
                continuationValid
        );
        if (hopTakeoff) {
            // vanillaの移動処理後に跳躍速度を確実に設定する、既存の操作感を担う処理。
            player.jump();

            if (!remoteServer) {
                sendStartGliding(client);
            } else if (!remoteRecovery.isActive()) {
                runRecoveryAction(client, remoteRecovery.begin());
            }
        }

        prevOnGround = onGround;
    }

    private void handleServerGlidingState(boolean gliding) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || client.getNetworkHandler() == null) {
            resetAllTracking();
            return;
        }

        // LAN公開中のホストを含む統合サーバーでは、サーバー応答を回復状態へ接続しない。
        if (client.isIntegratedServerRunning()) {
            remoteRecovery.reset();
            return;
        }

        boolean jumpHeld = player.input.playerInput.jump();
        boolean continuationValid = canContinueHopping(client, player, jumpHeld);
        RemoteGlidingRecovery.Action action = remoteRecovery.onServerGlidingState(
                gliding,
                !player.isOnGround(),
                continuationValid
        );
        runRecoveryAction(client, action);
    }

    private boolean canContinueHopping(MinecraftClient client, ClientPlayerEntity player, boolean jumpHeld) {
        if (!jumpHeld
                || !player.isAlive()
                || player.hasVehicle()
                || player.isTouchingWater()
                || player.hasStatusEffect(StatusEffects.LEVITATION)
                || client.getNetworkHandler() == null) {
            return false;
        }

        return LivingEntity.canGlideWith(
                player.getEquippedStack(EquipmentSlot.CHEST),
                EquipmentSlot.CHEST
        );
    }

    static boolean shouldUseRemoteRecovery(boolean integratedServerRunning) {
        return !integratedServerRunning;
    }

    static boolean isHopTakeoff(
            boolean jumpHeld,
            boolean prevOnGround,
            boolean airborne,
            boolean gliding,
            boolean continuationValid
    ) {
        return jumpHeld && prevOnGround && airborne && gliding && continuationValid;
    }

    private void runRecoveryAction(MinecraftClient client, RemoteGlidingRecovery.Action action) {
        if (action != RemoteGlidingRecovery.Action.SEND_REQUEST) {
            return;
        }

        if (!sendStartGliding(client)) {
            remoteRecovery.reset();
        }
    }

    private boolean sendStartGliding(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.getNetworkHandler() == null) {
            return false;
        }

        // このtrueはクライアント予測値であり、成功判定にはサーバー由来のFLAGS更新を使う。
        player.startGliding();
        client.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(
                player,
                ClientCommandC2SPacket.Mode.START_FALL_FLYING
        ));
        return true;
    }

    private void resetAllTracking() {
        remoteRecovery.reset();
        trackedPlayer = null;
        trackedWorld = null;
        prevOnGround = true;
    }
}
