package top.hibermod.fakeplayer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 自动换工具。
 *
 * <p>选择顺序（按需求）：
 * <ol>
 *   <li><b>同等级优先</b>：与"上一次使用的工具"同材质（铁配铁、钻配钻）；</li>
 *   <li><b>同种类优先</b>：同材质里再优先同类（镐/斧/锹/锄/剑）；</li>
 *   <li><b>格子顺序</b>：最后按快捷栏 0–8、主背包 9–35。</li>
 * </ol>
 * 是否"更合适"用原版 {@code ItemStack#getDestroySpeed} 判断，不写死任何表。
 * 这样工具断了以后不会突然换成别的材质。
 */
public final class FakeAutoTool {

    /** 每个假人上一次实际使用的手持工具（材质 + 种类）。 */
    private static final Map<UUID, String> LAST_MATERIAL = new HashMap<>();
    private static final Map<UUID, Integer> LAST_KIND = new HashMap<>();

    private FakeAutoTool() {
    }

    public static void forget(UUID uuid) {
        LAST_MATERIAL.remove(uuid);
        LAST_KIND.remove(uuid);
    }

    public static boolean ensureBestTool(ServerPlayer player, BlockPos pos) {
        var inv = player.getInventory();
        ItemStack held = inv.getItem(inv.selected);
        // 记录当前手持工具，作为后续替换的偏好来源
        if (!held.isEmpty() && held.getDestroySpeed(player.level().getBlockState(pos)) > 1.0F) {
            LAST_MATERIAL.put(player.getUUID(), FakeAutoArmor.materialOf(held));
            LAST_KIND.put(player.getUUID(), kindOf(held));
        }

        var state = FakeToggles.peek(player.getUUID());
        if (state == null || !state.autoTool) {
            return false;
        }
        BlockState blockState = player.level().getBlockState(pos);
        if (blockState.isAir()) {
            return false;
        }
        float heldSpeed = held.getDestroySpeed(blockState);
        String wantMaterial = LAST_MATERIAL.get(player.getUUID());
        int wantKind = LAST_KIND.getOrDefault(player.getUUID(), 0);

        int bestSlot = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            float speed = stack.getDestroySpeed(blockState);
            if (speed <= heldSpeed + 1.0E-4F) {
                continue;
            }
            int score = 0;
            if (wantMaterial != null && wantMaterial.equals(FakeAutoArmor.materialOf(stack))) {
                score += 100;
            }
            if (kindOf(stack) == wantKind) {
                score += 10;
            }
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) {
            return false;
        }
        ItemStack tool = inv.getItem(bestSlot);
        if (bestSlot <= 8) {
            inv.selected = bestSlot;
        } else {
            ItemStack hand = inv.getItem(inv.selected);
            inv.setItem(inv.selected, tool);
            inv.setItem(bestSlot, hand);
        }
        // 直接改背包后让打开的容器重新同步，避免客户端看到的槽位被回滚
        player.containerMenu.broadcastChanges();
        LAST_MATERIAL.put(player.getUUID(), FakeAutoArmor.materialOf(tool));
        LAST_KIND.put(player.getUUID(), kindOf(tool));
        FakePlayerLib.LOGGER.info("[fakeplayerlib] auto-tool {} slot={} <- {}",
                player.getGameProfile().getName(), bestSlot,
                net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(tool.getItem()).getPath());
        return true;
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
}
