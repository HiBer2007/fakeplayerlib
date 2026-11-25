package top.hibermod.fakeplayer.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import top.hibermod.fakeplayer.FakeConfig;
import top.hibermod.fakeplayer.FakePlayerFactory;
import top.hibermod.fakeplayer.FakePlayerLib;
import top.hibermod.fakeplayer.menu.FakeInventoryMenu;
import top.hibermod.fakeplayer.net.FakeActionPayload;

/**
 * 假人容器的服务端入口。
 *
 * <p>右键假人 → 打开真正的容器（槽位绑定假人背包），拖拽/丢出/Shift 移动全部由原版
 * {@link net.minecraft.world.inventory.AbstractContainerMenu} 承担；物品同步也走原版
 * 容器协议（{@code ServerPlayer#tick()} 里的 {@code broadcastChanges()}），
 * 不做任何快照或定时刷新，因此任何一次变化都会立刻到达客户端，也不会"过期停止"。
 */
public final class FakeGuiServer {

    private FakeGuiServer() {
    }

    /** 右键假人：打开容器界面。 */
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
        if (!player.hasPermissions(mod.config().level(FakeConfig.Action.GUI))) {
            return;
        }
        open(player, target);
    }

    /** 观察者 → 假人（面板打开期间记录），只用于状态下发。 */
    private static final java.util.Map<java.util.UUID, java.util.UUID> VIEWERS = new java.util.HashMap<>();
    /** 上次下发的状态，用于"只在变化时发送"。 */
    private static final java.util.Map<java.util.UUID, int[]> LAST_STATE = new java.util.HashMap<>();

    /** 打开容器：标题用假人名字，客户端界面据此执行对应指令。 */
    public static void open(ServerPlayer viewer, ServerPlayer fake) {
        VIEWERS.put(viewer.getUUID(), fake.getUUID());
        LAST_STATE.remove(viewer.getUUID());
        viewer.openMenu(new net.minecraft.world.SimpleMenuProvider(
                        (id, inv, p) -> new FakeInventoryMenu(id, inv, fake),
                        Component.literal(fake.getGameProfile().getName())),
                buf -> buf.writeVarInt(fake.getId()));
        // 立刻推一次，避免刚打开时显示占位值
        push(viewer, fake);
    }

    /** 每 tick 调用：只在假人状态发生变化时下发（不轮询展示、不过期）。 */
    public static void sendStates(net.minecraft.server.MinecraftServer server) {
        if (VIEWERS.isEmpty()) {
            return;
        }
        var it = VIEWERS.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            ServerPlayer viewer = server.getPlayerList().getPlayer(e.getKey());
            ServerPlayer fake = server.getPlayerList().getPlayer(e.getValue());
            if (viewer == null || fake == null
                    || !(viewer.containerMenu instanceof FakeInventoryMenu)) {
                it.remove();
                LAST_STATE.remove(e.getKey());
                continue;
            }
            int[] now = stateOf(fake);
            int[] last = LAST_STATE.get(e.getKey());
            if (last != null && java.util.Arrays.equals(last, now)) {
                continue;
            }
            LAST_STATE.put(e.getKey(), now);
            push(viewer, fake);
        }
    }

    private static int[] stateOf(ServerPlayer fake) {
        var toggles = top.hibermod.fakeplayer.FakeToggles.peek(fake.getUUID());
        return new int[]{
                fake.getInventory().selected,
                fake.getFoodData().getFoodLevel(),
                Math.round(fake.getFoodData().getSaturationLevel() * 10.0F),
                (int) fake.getHealth(),
                fake.getAbilities().flying ? 1 : 0,
                toggles != null && toggles.autoEat ? 1 : 0,
                toggles != null && toggles.autoFish ? 1 : 0,
                toggles != null && toggles.autoRestock ? 1 : 0,
                toggles != null && toggles.autoArmor ? 1 : 0,
                toggles != null
                        && toggles.sneak != top.hibermod.fakeplayer.FakeToggles.Sneak.OFF ? 1 : 0};
    }

    private static void push(ServerPlayer viewer, ServerPlayer fake) {
        int[] s = stateOf(fake);
        try {
            viewer.connection.send(new top.hibermod.fakeplayer.net.FakeStatePayload(
                    fake.getGameProfile().getName(), s[0], s[1], s[2], s[3], s[4] == 1,
                    s[5] == 1, s[6] == 1, s[7] == 1, s[8] == 1, s[9] == 1,
                    FakePlayerLib.get().config().restockLowDurability));
        } catch (Throwable t) {
            FakePlayerLib.LOGGER.debug("[fakeplayerlib] state push failed: {}", t.toString());
        }
    }

    /** 仅剩的 payload 动作（没有对应指令的辅助操作）。 */
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
                player.closeContainer();
            }
            case "bring" -> {
                fake.teleportTo(player.serverLevel(), player.getX(), player.getY(), player.getZ(),
                        fake.getYRot(), fake.getXRot());
                open(player, fake);
            }
            case "kill" -> {
                FakePlayerFactory.remove(server, fake);
                player.closeContainer();
            }
            default -> FakePlayerLib.LOGGER.debug("[fakeplayerlib] unknown panel action: {}", action);
        }
    }
}
