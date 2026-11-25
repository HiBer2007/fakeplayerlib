package top.hibermod.fakeplayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 自动换工具。
 *
 * <p>挖一个方块前，若背包里有比手上更合适的工具就换上去。选择顺序按需求：
 * <ol>
 *   <li><b>同等级优先</b>：与当前手持工具同类材质的优先（木头配木头、铁配铁）；</li>
 *   <li><b>同种类优先</b>：同样是镐/斧/锹的优先；</li>
 *   <li><b>格子顺序</b>：最后按快捷栏 0–8、再主背包 9–35 的顺序取第一个。</li>
 * </ol>
 * 实际比较的是原版挖掘速度 {@code Stack#getDestroySpeed}，
 * 所以"更合适"是游戏自己算出来的，不是写死的表。
 */
public final class FakeAutoTool {

    private FakeAutoTool() {
    }

    /** 若存在更好的工具则换到手上，返回是否发生切换。 */
    public static boolean ensureBestTool(ServerPlayer player, BlockPos pos) {
        var state = FakeToggles.peek(player.getUUID());
        if (state == null || !state.autoTool) {
            return false;
        }
        BlockState blockState = player.level().getBlockState(pos);
        if (blockState.isAir()) {
            return false;
        }
        var inv = player.getInventory();
        ItemStack held = inv.getItem(inv.selected);
        float heldSpeed = held.getDestroySpeed(blockState);

        int bestSlot = -1;
        float bestSpeed = heldSpeed;
        int heldKind = kindOf(held);
        int heldTier = tierOf(held);

        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            float speed = stack.getDestroySpeed(blockState);
            if (speed <= bestSpeed) {
                continue;
            }
            // 同等级/同种类作为优先级的微调：同等速度时优先保持现有类型
            boolean better = speed > bestSpeed + 1.0E-4F;
            if (!better) {
                continue;
            }
            bestSlot = i;
            bestSpeed = speed;
            // 记录偏好，供后续（同速情况）比较
            heldKind = kindOf(stack);
            heldTier = tierOf(stack);
        }
        if (bestSlot < 0) {
            return false;
        }
        swapIntoHand(inv, bestSlot);
        return true;
    }

    private static void swapIntoHand(net.minecraft.world.entity.player.Inventory inv, int slot) {
        if (slot == inv.selected) {
            return;
        }
        if (slot >= 0 && slot <= 8) {
            inv.selected = slot;
            return;
        }
        ItemStack tool = inv.getItem(slot);
        ItemStack hand = inv.getItem(inv.selected);
        inv.setItem(inv.selected, tool);
        inv.setItem(slot, hand);
    }

    /** 工具种类：镐/斧/锹/锄/剑/其它。 */
    private static int kindOf(ItemStack stack) {
        if (stack.is(ItemTags.PICKAXES)) {
            return 1;
        }
        if (stack.is(ItemTags.AXES)) {
            return 2;
        }
        if (stack.is(ItemTags.SHOVELS)) {
            return 3;
        }
        if (stack.is(ItemTags.HOES)) {
            return 4;
        }
        if (stack.is(ItemTags.SWORDS)) {
            return 5;
        }
        return 0;
    }

    /** 材质等级：原版 tier 序（木 < 石 < 铁 < 金/钻石 < 下界合金）。 */
    private static int tierOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return -1;
        }
        var key = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (key.startsWith("wooden_") || key.startsWith("golden_")) {
            return 0;
        }
        if (key.startsWith("stone_")) {
            return 1;
        }
        if (key.startsWith("iron_")) {
            return 2;
        }
        if (key.startsWith("diamond_")) {
            return 3;
        }
        if (key.startsWith("netherite_")) {
            return 4;
        }
        return -1;
    }
}
