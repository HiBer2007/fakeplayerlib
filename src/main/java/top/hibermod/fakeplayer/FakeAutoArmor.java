package top.hibermod.fakeplayer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;

/**
 * 自动替换护甲。
 *
 * <p>触发：任意护甲槽为空（刚损坏/掉落）。选择顺序按需求：
 * <ol>
 *   <li><b>同等级优先</b>：先找与刚损坏那件相同材质的；</li>
 *   <li><b>护甲等级从高到低</b>：下界合金 &gt; 钻石 &gt; 铁 &gt; 金 &gt; 锁链 &gt; 皮革；</li>
 *   <li><b>格子顺序</b>：快捷栏 0–8，再主背包 9–35。</li>
 * </ol>
 * 护甲槽索引：0 脚、1 腿、2 胸、3 头（{@code PlayerInventory.armor} 顺序）。
 */
public final class FakeAutoArmor {

    private FakeAutoArmor() {
    }

    /** 每 tick：补满空护甲槽。 */
    public static void tick(ServerPlayer player) {
        var state = FakeToggles.peek(player.getUUID());
        if (state == null || !state.autoArmor) {
            return;
        }
        var inv = player.getInventory();
        for (int slot = 0; slot < 4; slot++) {
            if (!inv.armor.get(slot).isEmpty()) {
                continue;
            }
            int bestSlot = -1;
            int bestRank = -1;
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty() || !isArmorFor(stack, slot)) {
                    continue;
                }
                int rank = tierOf(stack);
                if (rank > bestRank) {
                    bestRank = rank;
                    bestSlot = i;
                }
            }
            if (bestSlot < 0) {
                continue;
            }
            ItemStack armor = inv.getItem(bestSlot);
            inv.setItem(bestSlot, inv.armor.get(slot));
            inv.armor.set(slot, armor);
        }
    }

    /** 该物品是否属于这个护甲槽对应的部位。 */
    private static boolean isArmorFor(ItemStack stack, int slot) {
        return switch (slot) {
            case 0 -> stack.is(ItemTags.FOOT_ARMOR);
            case 1 -> stack.is(ItemTags.LEG_ARMOR);
            case 2 -> stack.is(ItemTags.CHEST_ARMOR);
            case 3 -> stack.is(ItemTags.HEAD_ARMOR);
            default -> false;
        };
    }

    /** 材质等级：皮革 0 < 锁链 1 < 金 2 < 铁 3 < 钻 4 < 下界合金 5。 */
    private static int tierOf(ItemStack stack) {
        String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (key.startsWith("leather_")) {
            return 0;
        }
        if (key.startsWith("chainmail_")) {
            return 1;
        }
        if (key.startsWith("golden_")) {
            return 2;
        }
        if (key.startsWith("iron_")) {
            return 3;
        }
        if (key.startsWith("diamond_")) {
            return 4;
        }
        if (key.startsWith("netherite_")) {
            return 5;
        }
        return -1;
    }
}
