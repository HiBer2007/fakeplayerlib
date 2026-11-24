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

    /** 打开面板的观察者 → 假人（key=观察者 UUID），用于实时刷新。 */
    private static final java.util.Map<java.util.UUID, java.util.UUID> VIEWERS = new java.util.HashMap<>();
    /** 观察者最后一次打开/刷新时的 tick，用于过期回收。 */
    private static final java.util.Map<java.util.UUID, Long> VIEWER_SEEN = new java.util.HashMap<>();

    public static void send(ServerPlayer viewer, ServerPlayer fake) {
        String json = buildJson(fake);
        try {
            viewer.connection.send(new FakeGuiPayload(json));
            VIEWERS.put(viewer.getUUID(), fake.getUUID());
            VIEWER_SEEN.put(viewer.getUUID(), (long) viewer.server.getTickCount());
        } catch (Throwable t) {
            FakePlayerLib.LOGGER.warn("[fakeplayerlib] failed to send fake player panel: {}", t.toString());
        }
    }

    /**
     * 面板打开期间推送最新内容（快捷栏选中、背包变化都能实时反映）。
     * 由 {@link FakePlayerLib} 每 10 tick 调一次；600 tick 未再打开则停止推送。
     */
    public static void refreshAll(net.minecraft.server.MinecraftServer server) {
        if (VIEWERS.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        var it = VIEWERS.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            java.util.UUID viewerId = entry.getKey();
            long seen = VIEWER_SEEN.getOrDefault(viewerId, now);
            if (now - seen > 600) {
                it.remove();
                VIEWER_SEEN.remove(viewerId);
                continue;
            }
            ServerPlayer viewer = server.getPlayerList().getPlayer(viewerId);
            ServerPlayer fake = server.getPlayerList().getPlayer(entry.getValue());
            if (viewer == null || fake == null) {
                it.remove();
                VIEWER_SEEN.remove(viewerId);
                continue;
            }
            try {
                viewer.connection.send(new FakeGuiPayload(buildJson(fake)));
            } catch (Throwable ignored) {
                it.remove();
                VIEWER_SEEN.remove(viewerId);
            }
        }
    }

    /** 关闭面板：停止推送。 */
    public static void forgetViewer(java.util.UUID viewerId) {
        VIEWERS.remove(viewerId);
        VIEWER_SEEN.remove(viewerId);
    }

    private static String buildJson(ServerPlayer fake) {
        StringBuilder inv = new StringBuilder();
        var container = fake.getInventory();
        // 0-8 快捷栏，9-35 主背包，36-39 护甲，40 副手：与客户端面板的槽位坐标一一对应
        for (int i = 0; i < Math.min(41, container.getContainerSize()); i++) {
            var stack = container.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (inv.length() > 0) {
                inv.append(',');
            }
            inv.append("{\"slot\":").append(i)
                    .append(",\"id\":\"").append(esc(
                            net.minecraft.core.registries.BuiltInRegistries.ITEM
                                    .getKey(stack.getItem()).toString()))
                    .append("\",\"count\":").append(stack.getCount()).append('}');
        }
        return "{\"name\":\"" + esc(fake.getGameProfile().getName()) + "\","
                + "\"dim\":\"" + esc(fake.level().dimension().location().toString()) + "\","
                + "\"x\":" + round(fake.getX()) + ","
                + "\"y\":" + round(fake.getY()) + ","
                + "\"z\":" + round(fake.getZ()) + ","
                + "\"health\":" + round(fake.getHealth()) + ","
                + "\"maxHealth\":" + round(fake.getMaxHealth()) + ","
                + "\"selected\":" + container.selected + ","
                + "\"food\":" + fake.getFoodData().getFoodLevel() + ","
                + "\"inv\":[" + inv + "]}";
    }

    /** 处理客户端按钮。 */
    public static void onAction(FakeActionPayload payload, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) {
            return;
        }
        String[] parts = payload.body().split("\\|", 3);
        if (parts.length < 2) {
            return;
        }
        String action = parts[0];
        String name = parts[1];
        String arg = parts.length > 2 ? parts[2] : "";

        // 权限与指令一致：等级来自配置，默认普通玩家只能操作自己名下的假人
        if (!top.hibermod.fakeplayer.command.PlayerCommand.mayOperate(player, name)
                && !player.hasPermissions(FakePlayerLib.get().config().level(
                        top.hibermod.fakeplayer.FakeConfig.Action.GUI))) {
            player.sendSystemMessage(Component.literal("§cnot allowed to operate this fake player"));
            return;
        }

        var server = player.getServer();
        if (server == null) {
            return;
        }
        ServerPlayer fake = FakePlayerLib.get().find(server, name);
        if (fake == null) {
            player.sendSystemMessage(Component.literal("§cfake player " + name + " no longer exists"));
            return;
        }

        switch (action) {
            case "tp_to" -> {
                player.teleportTo(fake.serverLevel(), fake.getX(), fake.getY(), fake.getZ(),
                        player.getYRot(), player.getXRot());
                player.sendSystemMessage(Component.literal("§ateleported to " + name));
            }
            case "bring" -> {
                fake.teleportTo(player.serverLevel(), player.getX(), player.getY(), player.getZ(),
                        fake.getYRot(), fake.getXRot());
                player.sendSystemMessage(Component.literal("§amoved " + name + " to you"));
                send(player, fake);
            }
            case "kill" -> {
                FakePlayerFactory.remove(server, fake);
                player.sendSystemMessage(Component.literal("§aremoved " + name));
            }
            case "use" -> {
                top.hibermod.fakeplayer.FakeAction.setUse(fake.getUUID(),
                        top.hibermod.fakeplayer.FakeAction.Mode.ONCE, 4);
                player.sendSystemMessage(Component.literal("§a" + name + " uses once"));
            }
            case "use_continuous" -> {
                top.hibermod.fakeplayer.FakeAction.setUse(fake.getUUID(),
                        top.hibermod.fakeplayer.FakeAction.Mode.CONTINUOUS, 1);
                player.sendSystemMessage(Component.literal("§a" + name + " keeps using"));
            }
            case "use_stop" -> {
                top.hibermod.fakeplayer.FakeAction.setUse(fake.getUUID(), null, 4);
                player.sendSystemMessage(Component.literal("§a" + name + " stopped using"));
            }
            case "attack" -> {
                top.hibermod.fakeplayer.FakeAction.setAttack(fake.getUUID(),
                        top.hibermod.fakeplayer.FakeAction.Mode.ONCE, 4);
                player.sendSystemMessage(Component.literal("§a" + name + " attacks once"));
            }
            case "attack_continuous" -> {
                top.hibermod.fakeplayer.FakeAction.setAttack(fake.getUUID(),
                        top.hibermod.fakeplayer.FakeAction.Mode.CONTINUOUS, 1);
                player.sendSystemMessage(Component.literal("§a" + name + " keeps attacking"));
            }
            case "attack_stop" -> {
                top.hibermod.fakeplayer.FakeAction.setAttack(fake.getUUID(), null, 4);
                player.sendSystemMessage(Component.literal("§a" + name + " stopped attacking"));
            }
            case "drop_all" -> {
                fake.getInventory().clearContent();
                player.sendSystemMessage(Component.literal("§adropped inventory of " + name));
                send(player, fake);
            }
            case "hotbar" -> {
                try {
                    int slot = Integer.parseInt(arg);
                    fake.getInventory().selected = Math.max(0, Math.min(8, slot - 1));
                    player.sendSystemMessage(Component.literal("§a" + name + " hotbar slot = " + slot));
                    send(player, fake);
                } catch (NumberFormatException ignored) {
                }
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
