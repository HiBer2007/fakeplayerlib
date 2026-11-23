package top.hibermod.fakeplayer;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 处理代理发来的 limbo 事件，维护"占位假人"。
 *
 * <p>场景：玩家 AFK 被 Velocity 挪去 limbo，原服里他的位置就空了。
 * 这里在原位置放一个假人顶着，玩家回来时移除。
 *
 * <p>假人名字用 {@code <原名>_afk}，UUID 由该名字派生，因此与真实玩家不会撞。
 */
public final class LimboLink {

    /** 原名 → 占位假人名 */
    private static final Map<String, String> PLACEHOLDERS = new ConcurrentHashMap<>();

    private LimboLink() {
    }

    public static void register(net.neoforged.neoforge.network.registration.PayloadRegistrar registrar) {
        registrar.playToServer(LimboPayload.TYPE, LimboPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> handle(payload.body(), ctx)));
        FakePlayerLib.LOGGER.info("[fakeplayerlib] registered channel hiberlimbo:main (limbo bridge)");
    }

    private static void handle(String body, IPayloadContext ctx) {
        String[] parts = body.split("\\|", 3);
        if (parts.length < 2) {
            FakePlayerLib.LOGGER.warn("[fakeplayerlib] unparsable limbo payload: {}", body);
            return;
        }
        String action = parts[0];
        String playerName = parts[1];

        ServerPlayer sender = ctx.player() instanceof ServerPlayer p ? p : null;
        MinecraftServer server = sender != null
                ? sender.getServer()
                : (FakePlayerLib.get() == null ? null : FakePlayerLib.get().server());

        if ("spawn_fake".equals(action)) {
            spawnPlaceholder(server, sender, playerName);
        } else if ("remove_fake".equals(action)) {
            removePlaceholder(server, playerName);
        } else {
            FakePlayerLib.LOGGER.debug("[fakeplayerlib] ignoring unknown limbo action: {}", action);
        }
    }

    private static void spawnPlaceholder(MinecraftServer server, ServerPlayer sender, String playerName) {
        if (server == null) {
            return;
        }
        String fakeName = playerName + "_afk";
        if (FakePlayerLib.get().find(server, fakeName) != null) {
            return;
        }
        // 优先用同连接上的玩家（就是正要被挪走的那个），拿他当前的坐标
        ServerPlayer origin = sender;
        if (origin == null) {
            origin = server.getPlayerList().getPlayerByName(playerName);
        }
        if (origin == null) {
            FakePlayerLib.LOGGER.warn("[fakeplayerlib] no position for {}, skipping placeholder fake player", playerName);
            return;
        }
        ServerLevel level = origin.serverLevel();
        try {
            FakePlayerFactory.spawn(server, level, fakeName,
                    origin.getX(), origin.getY(), origin.getZ());
            PLACEHOLDERS.put(playerName, fakeName);
            FakePlayerLib.LOGGER.info("[fakeplayerlib] placed placeholder fake player {} for {} @ {} {} {}",
                    playerName, fakeName, origin.getX(), origin.getY(), origin.getZ());
        } catch (Exception e) {
            FakePlayerLib.LOGGER.error("[fakeplayerlib] failed to place placeholder fake player", e);
        }
    }

    private static void removePlaceholder(MinecraftServer server, String playerName) {
        if (server == null) {
            return;
        }
        String fakeName = PLACEHOLDERS.remove(playerName);
        if (fakeName == null) {
            fakeName = playerName + "_afk";
        }
        ServerPlayer fake = FakePlayerLib.get().find(server, fakeName);
        if (fake != null) {
            try {
                FakePlayerFactory.remove(server, fake);
                FakePlayerLib.LOGGER.info("[fakeplayerlib] removed placeholder fake player of {}", playerName);
            } catch (Exception e) {
                FakePlayerLib.LOGGER.error("[fakeplayerlib] failed to remove placeholder fake player", e);
            }
        }
    }
}
