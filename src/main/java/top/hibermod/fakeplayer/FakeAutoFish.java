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
    /** 缓存 DATA_BITING 访问器。 */
    private static Object BITE_ACCESSOR;

    private FakeAutoFish() {
    }

    public static void forget(java.util.UUID uuid) {
        FakeTiming.forget(uuid);
    }

    public static void tick(ServerPlayer player, long gameTime) {
        var state = FakeToggles.peek(player.getUUID());
        if (state == null || !state.autoFish) {
            return;
        }
        ItemStack main = player.getMainHandItem();
        if (main.isEmpty() || !(main.getItem() instanceof net.minecraft.world.item.FishingRodItem)) {
            return;
        }
        var id = player.getUUID();
        // 有漂时每 tick 都检查咬钩（高附魔下咬钩窗口很短，不能再被抛竿冷却挡住）
        FishingHook hook = findHook(player);
        if (hook == null) {
            if (!FakeTiming.readyTicks(id, "fish-cooldown", 6, gameTime)) {
                return;
            }
            if (player.isUsingItem()) {
                return;
            }
            if (FakeTiming.elapsedTicks(id, "fish-recast", 20, gameTime)) {
                FakeClient.use(player);
                FakeTiming.markTicks(id, "fish-cast", gameTime);
            }
            return;
        }
        if (player.isUsingItem()) {
            return;
        }
        if (!FakeTiming.elapsedTicks(id, "fish-cast", 30, gameTime)) {
            return;                                          // 刚抛出，等落水
        }
        // 只认原版"鱼上钩"这一权威状态：
        // 服务端在鱼咬钩时把 FishingHook 的同步字段 DATA_BITING 置为 true（客户端就是靠它放水花音效），
        // 所以这里读取同一份同步数据，而不是猜浮漂的速度。之前用 dy < 0 判定，
        // 浮漂刚落水时就有向下速度，才会"一落水就收杆"。
        if (isBiting(hook) || hook.getHookedIn() != null) {
            FakeClient.use(player);
            FakeTiming.markTicks(id, "fish-recast", gameTime);
        }
    }

    /** 读取原版同步的 DATA_BITING 标记（字段是 private static，反射缓存一次）。 */
    private static boolean isBiting(FishingHook hook) {
        try {
            if (BITE_ACCESSOR == null) {
                var f = FishingHook.class.getDeclaredField("DATA_BITING");
                f.setAccessible(true);
                BITE_ACCESSOR = f.get(null);
            }
            if (BITE_ACCESSOR instanceof net.minecraft.network.syncher.EntityDataAccessor<?> acc) {
                @SuppressWarnings("unchecked")
                var typed = (net.minecraft.network.syncher.EntityDataAccessor<Boolean>) acc;
                return Boolean.TRUE.equals(hook.getEntityData().get(typed));
            }
        } catch (Throwable t) {
            FakePlayerLib.LOGGER.debug("[fakeplayerlib] bite accessor unavailable: {}", t.toString());
        }
        return false;
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
