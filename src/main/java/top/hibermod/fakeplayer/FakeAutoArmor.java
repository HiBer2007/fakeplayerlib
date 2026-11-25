package top.hibermod.fakeplayer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 自动替换护甲。
 *
 * <p><b>记忆原本穿的材质</b>：每 tick 记录各护甲槽里当前装备的材质；
 * 一旦某个槽变空（护甲爆掉），优先补同样材质的（锁链破了补锁链），
 * 只有找不到同材质时才按材质等级从高到低，最后按格子顺序。
 * 这解决了"锁链头盔破了却拿了铁头盔"的问题。
 */
public final class FakeAutoArmor {

    /** 每个假人各护甲槽最近一次装备的物品（用于记忆材质）。 */
    private static final Map<UUID, String[]> LAST_MATERIAL = new HashMap<>();

    private FakeAutoArmor() {
    }

    public static void forget(UUID uuid) {
        LAST_MATERIAL.remove(uuid);
    }

    public static void tick(ServerPlayer player, long gameTime) {
        var inv = player.getInventory();
        String[] memory = LAST_MATERIAL.computeIfAbsent(player.getUUID(), k -> new String[4]);

        // 先更新记忆（记录当前仍穿着的护甲材质）
        for (int slot = 0; slot < 4; slot++) {
            ItemStack worn = inv.armor.get(slot);
            if (!worn.isEmpty()) {
                memory[slot] = materialOf(worn);
            }
        }

        var state = FakeToggles.peek(player.getUUID());
        if (state == null || !state.autoArmor) {
            return;
        }
        // 与 tick 速率无关的节流
        if (!FakeTiming.readyTicks(player.getUUID(), "armor-check", 5, gameTime)) {
            return;
        }
        for (int slot = 0; slot < 4; slot++) {
            if (!inv.armor.get(slot).isEmpty()) {
                continue;
            }
            String wanted = memory[slot];
            int bestSlot = -1;
            int bestScore = Integer.MIN_VALUE;
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty() || !isArmorFor(stack, slot)) {
                    continue;
                }
                // 同材质优先，其次材质等级从高到低；同分时按格子顺序（先遇到的优先）
                int score = materialOf(stack).equals(wanted) ? 100 : 0;
                score += tierOf(stack);
                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = i;
                }
            }
            if (bestSlot < 0) {
                continue;
            }
            ItemStack armor = inv.getItem(bestSlot);
            inv.setItem(bestSlot, inv.armor.get(slot));
            inv.armor.set(slot, armor);
            // 直接改背包后让打开的容器重新同步
            player.containerMenu.broadcastChanges();
            memory[slot] = materialOf(armor);
            FakePlayerLib.LOGGER.info("[fakeplayerlib] auto-armor {} slot={} <- {}",
                    player.getGameProfile().getName(), slot,
                    net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(armor.getItem()).getPath());
        }
    }

    private static boolean isArmorFor(ItemStack stack, int slot) {
        return switch (slot) {
            case 0 -> stack.is(ItemTags.FOOT_ARMOR);
            case 1 -> stack.is(ItemTags.LEG_ARMOR);
            case 2 -> stack.is(ItemTags.CHEST_ARMOR);
            case 3 -> stack.is(ItemTags.HEAD_ARMOR);
            default -> false;
        };
    }

    /** 材质前缀：leather / chainmail / iron / golden / diamond / netherite / 其它。 */
    public static String materialOf(ItemStack stack) {
        String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        int idx = key.indexOf('_');
        return idx > 0 ? key.substring(0, idx) : key;
    }

    private static int tierOf(ItemStack stack) {
        return switch (materialOf(stack)) {
            case "leather" -> 0;
            case "chainmail" -> 1;
            case "golden" -> 2;
            case "iron" -> 3;
            case "diamond" -> 4;
            case "netherite" -> 5;
            default -> -1;
        };
    }
}
