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

    /** 打开容器：标题用假人名字，客户端界面据此执行对应指令。 */
    public static void open(ServerPlayer viewer, ServerPlayer fake) {
        viewer.openMenu(new net.minecraft.world.SimpleMenuProvider(
                        (id, inv, p) -> new FakeInventoryMenu(id, inv, fake),
                        Component.literal(fake.getGameProfile().getName())),
                buf -> buf.writeVarInt(fake.getId()));
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
