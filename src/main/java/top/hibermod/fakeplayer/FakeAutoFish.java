package top.hibermod.fakeplayer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 自动钓鱼（移植 Carpet 的 AutoFish 思路）。
 *
 * <p>状态机：
 * <ol>
 *   <li>手上有钓竿且水里有自己的浮漂 → 等待；</li>
 *   <li>浮漂进入"咬钩"状态（{@code getHookedIn() != null} 或
 *       {@code currentState == HOOKED_IN_ENTITY}）→ 立刻右键收杆；</li>
 *   <li>收杆后等待 {@value #RECAST_DELAY} tick 再抛竿，避免连点；</li>
 *   <li>浮漂超时（{@value #MAX_WAIT} tick）也收杆重抛，防止卡死。</li>
 * </ol>
 * 抛竿/收杆都走真实的右键入口（{@link FakeClient#use}），所以钓鱼判定是原版的。
 */
public final class FakeAutoFish {

    private static final int RECAST_DELAY = 20;
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
            // 没漂 → 抛竿
            FakeClient.use(player);
            return;
        }
        // 咬钩判定：挂上了实体（鱼咬钩时 hookedIn 会被设置），或浮漂明显下沉
        boolean biting = hook.getHookedIn() != null || hook.getDeltaMovement().y < -0.05D;
        if (biting || hook.tickCount > MAX_WAIT) {
            FakeClient.use(player);          // 收杆
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
