package top.hibermod.fakeplayer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;

/**
 * 自动钓鱼（参考 Carpet 的 AutoFish 状态机，按本模组右键实现调整）。
 *
 * <p>全部等待都用<b>毫秒</b>（{@link FakeTiming}），所以在 /tick rate 提高时不会疯狂抛收杆。
 *
 * <ol>
 *   <li>没浮漂 → 抛竿，并记下抛竿时间；</li>
 *   <li>抛竿后 1500ms 内不判定咬钩（等浮漂落水）；</li>
 *   <li>咬钩（挂上实体，或水中且明显下沉）→ 收杆；</li>
 *   <li>收杆后 1000ms 再抛下一杆。</li>
 * </ol>
 */
public final class FakeAutoFish {

    private static final long MIN_AGE_MS = 1500L;
    private static final long RECAST_MS = 1000L;
    private static final long CAST_COOLDOWN_MS = 300L;

    private FakeAutoFish() {
    }

    public static void forget(java.util.UUID uuid) {
        FakeTiming.forget(uuid);
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
        var id = player.getUUID();
        if (!FakeTiming.ready(id, "fish-cooldown", CAST_COOLDOWN_MS)) {
            return;
        }
        if (player.isUsingItem()) {
            return;
        }
        FishingHook hook = findHook(player);
        if (hook == null) {
            if (FakeTiming.elapsed(id, "fish-recast", RECAST_MS)) {
                FakeClient.use(player);
                FakeTiming.mark(id, "fish-cast");
            }
            return;
        }
        if (!FakeTiming.elapsed(id, "fish-cast", MIN_AGE_MS)) {
            return;                                          // 刚抛出，等落水
        }
        boolean biting = hook.getHookedIn() != null
                || (hook.isInWater() && hook.getDeltaMovement().y < -0.1D);
        if (biting) {
            FakeClient.use(player);
            FakeTiming.mark(id, "fish-recast");
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
