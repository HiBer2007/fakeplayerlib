package top.hibermod.fakeplayer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 假人动作包（对齐 Carpet 的 {@code EntityPlayerActionPack} 语义）。
 *
 * <p>三种模式：
 * <ul>
 *   <li>{@code once}  —— 下一个 tick 做一次；</li>
 *   <li>{@code continuous} —— 每 tick 都做（use/attack 的"按住"）；</li>
 *   <li>{@code interval} —— 每 n tick 做一次。</li>
 * </ul>
 *
 * <p>指令（{@code /player <name> use continuous}）和 GUI 按钮改的都是这里的状态，
 * 所以不存在"两套开关打架"。
 */
public final class FakeAction {

    public enum Mode {
        ONCE, CONTINUOUS, INTERVAL
    }

    /** 一个持续动作。 */
    public static final class Pack {
        public Mode useMode = null;
        public int useInterval = 4;
        public long useNextTick = 0;

        public Mode attackMode = null;
        public int attackInterval = 4;
        public long attackNextTick = 0;

        public boolean empty() {
            return useMode == null && attackMode == null;
        }
    }

    private static final Map<UUID, Pack> PACKS = new HashMap<>();

    private FakeAction() {
    }

    public static Pack of(UUID uuid) {
        return PACKS.computeIfAbsent(uuid, k -> new Pack());
    }

    public static void clear(UUID uuid) {
        PACKS.remove(uuid);
    }

    /** 当前持有动作包的实体（含被当作操作目标的真人玩家）。 */
    public static java.util.Set<UUID> active() {
        return new java.util.HashSet<>(PACKS.keySet());
    }

    public static void setUse(UUID uuid, Mode mode, int interval) {
        Pack p = of(uuid);
        p.useMode = mode;
        p.useInterval = Math.max(1, interval);
        p.useNextTick = 0;
    }

    public static void setAttack(UUID uuid, Mode mode, int interval) {
        Pack p = of(uuid);
        p.attackMode = mode;
        p.attackInterval = Math.max(1, interval);
        p.attackNextTick = 0;
    }

    /** 每 tick 由 FakePlayerLib 驱动。 */
    public static void tick(ServerPlayer player, long gameTime) {
        Pack p = PACKS.get(player.getUUID());
        if (p == null || p.empty()) {
            return;
        }
        if (p.useMode != null && due(p.useMode, p.useInterval, p.useNextTick, gameTime)) {
            // 正在使用物品（进食/拉弓/喝药）时不能重复发右键，否则会重置使用进度
            if (!player.isUsingItem()) {
                p.useNextTick = next(p.useMode, p.useInterval, gameTime);
                use(player);
            }
            if (p.useMode == Mode.ONCE && !player.isUsingItem()) {
                p.useMode = null;
            }
        }
        if (p.attackMode != null && due(p.attackMode, p.attackInterval, p.attackNextTick, gameTime)) {
            p.attackNextTick = next(p.attackMode, p.attackInterval, gameTime);
            // 每 tick 都当作"按住左键"：挖方块需要持续 tick（进度在 gameMode.tick() 里推进），
            // 打实体由 FakeClient 内部按攻击冷却过滤，避免一 tick 打无数次
            top.hibermod.fakeplayer.FakeClient.leftClick(player);
            if (p.attackMode == Mode.ONCE) {
                p.attackMode = null;
            }
        }
    }

    private static boolean due(Mode mode, int interval, long next, long now) {
        if (mode == Mode.CONTINUOUS) {
            return true;
        }
        return now >= next;
    }

    private static long next(Mode mode, int interval, long now) {
        if (mode == Mode.CONTINUOUS) {
            return now + 1;
        }
        if (mode == Mode.INTERVAL) {
            return now + interval;
        }
        return now + 1;
    }

    /**
     * 一次"右键"：交给虚拟客户端构造原版 UseItem/UseItemOn 包，
     * 服务端的 {@link net.minecraft.server.network.ServerGamePacketListenerImpl}
     * 会按原版逻辑处理（先方块、再实体、最后物品），模组改动自然生效。
     */
    private static void use(ServerPlayer player) {
        player.swing(InteractionHand.MAIN_HAND, true);
        top.hibermod.fakeplayer.FakeClient.use(player);
    }

    /**
     * 与原版客户端一致的攻击目标选择：沿视线做实体射线检测。
     *
     * <p>之前用"附近最近实体"是错的 —— 会打到背后/侧面的东西，
     * 表现就像"打了没有反馈"。原版 {@code LocalPlayer} 走的是
     * {@code ProjectileUtil.getEntityHitResult}，Carpet 也是同样的射线追踪。
     */
    private static Entity nearestTarget(ServerPlayer player) {
        double reach = player.gameMode.isCreative() ? 5.0 : 4.5;
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Vec3 end = eye.add(look.scale(reach));
        AABB box = player.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.0);
        var hit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(
                player, eye, end, box,
                e -> !e.isSpectator() && e.isPickable() && e != player,
                reach * reach);
        return hit == null ? null : hit.getEntity();
    }
}
