package top.hibermod.fakeplayer.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import top.hibermod.fakeplayer.FakePlayerFactory;
import top.hibermod.fakeplayer.FakePlayerLib;
import top.hibermod.fakeplayer.net.FakeActionPayload;
import top.hibermod.fakeplayer.net.FakeGuiPayload;

/**
 * 假人右键面板的服务端实现。
 *
 * <p>交互路径：玩家右键假人 → 服务端拦下这次交互（不打开原版交易/骑乘界面）→
 * 下发 {@link FakeGuiPayload} → 客户端弹出面板 →
 * 按按钮回传 {@link FakeActionPayload} → 这里执行。
 *
 * <p>所有操作都要求 op 等级 2，假人面板本质是管理工具，不该给普通玩家用。
 */
public final class FakeGuiServer {

    private FakeGuiServer() {
    }

    /** 右键假人：拦下交互并下发给客户端。 */
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!(event.getTarget() instanceof ServerPlayer target)) {
            return;
        }
        FakePlayerLib mod = FakePlayerLib.get();
        if (mod == null || !mod.fakes().containsKey(target.getUUID())) {
            return;
        }
        event.setCanceled(true);
        if (!player.hasPermissions(2)) {
            return;
        }
        send(player, target);
    }

    public static void send(ServerPlayer viewer, ServerPlayer fake) {
        String json = buildJson(fake);
        try {
            viewer.connection.send(new FakeGuiPayload(json));
        } catch (Throwable t) {
            FakePlayerLib.LOGGER.warn("[fakeplayerlib] failed to send fake player panel: {}", t.toString());
        }
    }

    private static String buildJson(ServerPlayer fake) {
        return "{\"name\":\"" + esc(fake.getGameProfile().getName()) + "\","
                + "\"dim\":\"" + esc(fake.level().dimension().location().toString()) + "\","
                + "\"x\":" + round(fake.getX()) + ","
                + "\"y\":" + round(fake.getY()) + ","
                + "\"z\":" + round(fake.getZ()) + ","
                + "\"health\":" + round(fake.getHealth()) + ","
                + "\"maxHealth\":" + round(fake.getMaxHealth()) + "}";
    }

    /** 处理客户端按钮。 */
    public static void onAction(FakeActionPayload payload, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) {
            return;
        }
        String[] parts = payload.body().split("\\|", 2);
        if (parts.length < 2) {
            return;
        }
        String action = parts[0];
        String name = parts[1];

        if (!player.hasPermissions(2)) {
            player.sendSystemMessage(Component.literal("§c没有权限操作假人"));
            return;
        }

        var server = player.getServer();
        if (server == null) {
            return;
        }
        ServerPlayer fake = FakePlayerLib.get().find(server, name);
        if (fake == null) {
            player.sendSystemMessage(Component.literal("§c假人 " + name + " 已不存在"));
            return;
        }

        switch (action) {
            case "tp_to" -> {
                player.teleportTo(fake.serverLevel(), fake.getX(), fake.getY(), fake.getZ(),
                        player.getYRot(), player.getXRot());
                player.sendSystemMessage(Component.literal("§a已传送到假人 " + name));
            }
            case "bring" -> {
                fake.teleportTo(player.serverLevel(), player.getX(), player.getY(), player.getZ(),
                        fake.getYRot(), fake.getXRot());
                player.sendSystemMessage(Component.literal("§a假人 " + name + " 已传送到你身边"));
                send(player, fake);
            }
            case "kill" -> {
                FakePlayerFactory.remove(server, fake);
                player.sendSystemMessage(Component.literal("§a已移除假人 " + name));
            }
            case "refresh" -> send(player, fake);
            default -> FakePlayerLib.LOGGER.debug("[fakeplayerlib] unknown panel action: {}", action);
        }
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
