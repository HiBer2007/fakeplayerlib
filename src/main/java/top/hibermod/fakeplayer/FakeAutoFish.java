package top.hibermod.fakeplayer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 自动钓鱼（参考 Carpet 的 AutoFish 状态机，并按本模组的右键实现做了调整）。
 *
 * <p>状态机：
 * <ol>
 *   <li><b>没有浮漂</b>：抛竿（右键），随后进入冷却，避免同一 tick 又去收杆 ——
 *       之前"挥杆音效和收杆音效同时出现"就是缺这一步；</li>
 *   <li><b>有浮漂但还很新</b>（{@code tickCount < 40}）：什么都不做，等它落水；</li>
 *   <li><b>判定咬钩</b>：挂上了实体，或浮漂在水中且明显下沉；</li>
 *   <li>收杆后等 {@value #RECAST_DELAY} tick 再抛下一杆。</li>
 * </ol>
 */
public final class FakeAutoFish {

    private static final int RECAST_DELAY = 20;
    /** 浮漂至少存活这么多 tick 才允许判定咬钩。 */
    private static final int MIN_AGE = 40;
    private static final int MAX_WAIT = 1200;

    private static final Map<UUID, Integer> COOLDOWN = new HashMap<>();

    private FakeAutoFish() {
    }

    public static void forget(UUID uuid) {
        COOLDOWN.remove(uuid);
    }

    public static void tick(ServerPlayer player) {
        var state = FakeToggles.peek(player.getUUID());
        if (state == null || !state.autoFish) {
            return;
        }
        ItemStack main = player.getMainHandItem();
        if (main.isEmpty() || !(main.getItem() instanceof net.minecraft.world.item.FishingRodItem)) {
            return;
        }
        int cd = COOLDOWN.getOrDefault(player.getUUID(), 0);
        if (cd > 0) {
            COOLDOWN.put(player.getUUID(), cd - 1);
            return;
        }
        if (player.isUsingItem()) {
            return;
        }
        FishingHook hook = findHook(player);
        if (hook == null) {
            FakeClient.use(player);                       // 抛竿
            COOLDOWN.put(player.getUUID(), 10);           // 抛完先别急着收
            return;
        }
        if (hook.tickCount < MIN_AGE) {
            return;                                       // 刚抛出，等落水
        }
        boolean biting = hook.getHookedIn() != null
                || (hook.isInWater() && hook.getDeltaMovement().y < -0.1D);
        if (biting || hook.tickCount > MAX_WAIT) {
            FakeClient.use(player);                       // 收杆
            COOLDOWN.put(player.getUUID(), RECAST_DELAY);
        }
    }

    private static FishingHook findHook(ServerPlayer player) {
        for (var e : player.level().getEntities(player, player.getBoundingBox().inflate(48.0))) {
            if (e instanceof FishingHook hook && hook.getPlayerOwner() == player) {
                return hook;
            }
        }
        return null;
    }
}
