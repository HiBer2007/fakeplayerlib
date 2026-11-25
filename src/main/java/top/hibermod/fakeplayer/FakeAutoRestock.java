package top.hibermod.fakeplayer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 自动补货（原"自动换工具"）。
 *
 * <p>每 tick 检查主手：手持物<b>空/损坏</b>时从背包补一件。规则分层：
 * <ol>
 *   <li><b>完全相同的物品</b>（同一 registry id）优先 —— 例如鱼竿断了补鱼竿、
 *       武器断了补同类武器；</li>
 *   <li><b>有等级/类别标签的物品</b>（镐/斧/锹/锄/剑等）再按：同材质 → 同种类 →
 *       更高等级；</li>
 *   <li>都没有时按<b>格子顺序</b>（快捷栏 0–8，再主背包 9–35）。</li>
 * </ol>
 * 因此"自动补工具"只是这条通用规则的一个特例，鱼竿、三叉戟、方块、食物同样适用。
 */
public final class FakeAutoRestock {

    private static final Map<UUID, String> LAST_MATERIAL = new HashMap<>();
    private static final Map<UUID, Integer> LAST_KIND = new HashMap<>();

    private FakeAutoRestock() {
    }

    public static void forget(UUID uuid) {
        LAST_MATERIAL.remove(uuid);
        LAST_KIND.remove(uuid);
    }

    /** 每 tick 调用：主手空了或只剩 1 点耐久就补货。 */
    public static void tick(ServerPlayer player) {
        var state = FakeToggles.peek(player.getUUID());
        if (state == null || !state.autoRestock) {
            return;
        }
        if (!FakeTiming.readyTicks(player.getUUID(), "restock", 2, player.level().getGameTime())) {
            return;
        }
        ensureHeld(player);
    }

    /** 主手需要补货时补一件，返回是否发生更换。 */
    public static boolean ensureHeld(ServerPlayer player) {
        var inv = player.getInventory();
        ItemStack held = inv.getItem(inv.selected);
        if (!needsReplace(held)) {
            remember(player, held);
            return false;
        }
        int slot = findReplacement(player, held);
        if (slot < 0) {
            return false;
        }
        ItemStack item = inv.getItem(slot);
        inv.setItem(slot, held);
        inv.setItem(inv.selected, item);
        player.containerMenu.broadcastChanges();
        remember(player, item);
        FakePlayerLib.LOGGER.info("[fakeplayerlib] restock {} slot={} <- {}",
                player.getGameProfile().getName(), slot,
                BuiltInRegistries.ITEM.getKey(item.getItem()).getPath());
        return true;
    }

    /** 空的、或耐久只剩 1（下一次使用就碎）都需要换。 */
    private static boolean needsReplace(ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        return stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= 1;
    }

    private static void remember(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        LAST_MATERIAL.put(player.getUUID(), FakeAutoArmor.materialOf(stack));
        LAST_KIND.put(player.getUUID(), kindOf(stack));
    }

    private static int findReplacement(ServerPlayer player, ItemStack broken) {
        var inv = player.getInventory();
        String wantId = broken.isEmpty() ? null
                : BuiltInRegistries.ITEM.getKey(broken.getItem()).toString();
        String wantMaterial = broken.isEmpty()
                ? LAST_MATERIAL.get(player.getUUID()) : FakeAutoArmor.materialOf(broken);
        int wantKind = broken.isEmpty()
                ? LAST_KIND.getOrDefault(player.getUUID(), 0) : kindOf(broken);

        int bestSlot = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < 36; i++) {
            if (i == inv.selected) {
                continue;
            }
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || needsReplace(stack)) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            int score;
            if (wantId != null && wantId.equals(id)) {
                score = 1000;
            } else if (wantKind != 0 && kindOf(stack) == wantKind) {
                score = 100;
                if (wantMaterial != null && wantMaterial.equals(FakeAutoArmor.materialOf(stack))) {
                    score += 50;
                }
            } else if (wantKind == 0 && wantId != null && FakeAutoArmor.materialOf(stack)
                    .equals(wantMaterial)) {
                score = 40;
            } else {
                continue;
            }
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    /** 工具/武器类别：镐/斧/锹/锄/剑/三叉戟/其它(0)。 */
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
        if (stack.is(ItemTags.TRIDENT_ENCHANTABLE)) {
            return 6;
        }
        if (stack.getItem() instanceof net.minecraft.world.item.FishingRodItem) {
            return 7;
        }
        return 0;
    }
}
