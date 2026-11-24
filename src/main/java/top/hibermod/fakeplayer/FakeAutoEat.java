package top.hibermod.fakeplayer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 自动进食。
 *
 * <p>触发条件（阈值在 {@link FakeConfig} 里）：血量低于 {@code autoEatHealth} 或
 * 饱食度低于 {@code autoEatFood}，且当前手上不是食物/正在使用物品。
 *
 * <p>选食物顺序与需求一致：<b>先快捷栏 0–8 从左到右，再主背包 9–35 按行优先</b>。
 * 拿到食物后把它和当前选中槽交换，走原版右键路径（{@link FakeClient#use}）进食，
 * 吃完（物品被消耗或使用结束）再把原来的物品换回去，避免打乱假人背包顺序。
 */
public final class FakeAutoEat {

    /** 每个假人正在进行的换手恢复。 */
    private static final class Pending {
        int slot = -1;            // 食物原来所在槽位
        ItemStack original = ItemStack.EMPTY;  // 原选中槽里的物品
    }

    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private FakeAutoEat() {
    }

    public static void forget(UUID uuid) {
        PENDING.remove(uuid);
    }

    /** 每 tick 由 FakePlayerLib 调用。 */
    public static void tick(ServerPlayer player) {
        FakeConfig cfg = FakePlayerLib.get().config();
        if (!cfg.autoEatEnabled) {
            return;
        }
        var inv = player.getInventory();
        Pending pending = PENDING.get(player.getUUID());

        // 1) 吃完/中断后还原槽位：选中的剩余食物放回原槽，原物品回到手上
        if (pending != null) {
            if (player.isUsingItem()) {
                return;
            }
            if (pending.slot >= 0) {
                ItemStack remaining = inv.getItem(inv.selected);
                inv.setItem(inv.selected, pending.original);
                inv.setItem(pending.slot, remaining);
            }
            PENDING.remove(player.getUUID());
            return;
        }

        if (player.isUsingItem()) {
            return;
        }
        boolean hungry = player.getFoodData().getFoodLevel() <= cfg.autoEatFood;
        boolean hurt = player.getHealth() <= cfg.autoEatHealth;
        if (!hungry && !hurt) {
            return;
        }

        int slot = findFood(player, hungry);
        if (slot < 0) {
            return;
        }
        Pending p = new Pending();
        p.slot = slot;
        p.original = inv.getItem(inv.selected).copy();
        ItemStack food = inv.getItem(slot).copy();
        inv.setItem(inv.selected, food);
        inv.setItem(slot, p.original.isEmpty() ? ItemStack.EMPTY : p.original);
        PENDING.put(player.getUUID(), p);
        // 走真实右键：开始进食，后续由原版 usingItem 逻辑推进
        FakeClient.use(player);
    }

    /** 快捷栏优先，然后主背包按行优先；只认"可直接吃"的食物。 */
    private static int findFood(ServerPlayer player, boolean hungryOnly) {
        var inv = player.getInventory();
        for (int i = 0; i <= 8; i++) {
            if (isFood(player, inv.getItem(i), hungryOnly)) {
                return i;
            }
        }
        for (int i = 9; i <= 35; i++) {
            if (isFood(player, inv.getItem(i), hungryOnly)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isFood(ServerPlayer player, ItemStack stack, boolean hungryOnly) {
        if (stack.isEmpty()) {
            return false;
        }
        var props = stack.getFoodProperties(player);
        if (props == null) {
            return false;
        }
        // 饱食度不足时优先正餐；仅掉血且不饿时也允许吃（金苹果等）
        return !hungryOnly || props.nutrition() > 0;
    }
}
