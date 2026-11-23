package top.hibermod.fakeplayer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
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
            p.useNextTick = next(p.useMode, p.useInterval, gameTime);
            player.swing(InteractionHand.MAIN_HAND, true);
            player.gameMode.useItem(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
            if (p.useMode == Mode.ONCE) {
                p.useMode = null;
            }
        }
        if (p.attackMode != null && due(p.attackMode, p.attackInterval, p.attackNextTick, gameTime)) {
            // 与真实客户端一致：攻击冷却（充能）未就绪就不发攻击。
            // 连续攻击每 tick 都打会让充能恒为 0 —— 原版击退/伤害分支都不成立，
            // 表现出来就是"完全没有击退"。
            float charge = player.getAttackStrengthScale(0.5F);
            if (p.attackMode == Mode.CONTINUOUS && charge < 0.95F) {
                return;
            }
            p.attackNextTick = next(p.attackMode, p.attackInterval, gameTime);
            Entity target = nearestTarget(player);
            if (target != null) {
                Vec3 before = target.getDeltaMovement();
                top.hibermod.fakeplayer.FakeInput.attack(player, target);
                if (gameTime % 20 == 0) {
                    FakePlayerLib.LOGGER.info(
                            "[fakeplayerlib] attack diag: charge={} sprint={} hurt={} targetDelta={} -> {}",
                            charge, player.isSprinting(), target.isAlive(),
                            before, target.getDeltaMovement());
                }
            } else {
                player.swing(InteractionHand.MAIN_HAND, true);
            }
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

    private static Entity nearestTarget(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Vec3 end = eye.add(look.scale(3.0));
        AABB box = new AABB(eye, end).inflate(1.0);
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : player.level().getEntities(player, box)) {
            if (!e.isAlive() || e == player) {
                continue;
            }
            double d = e.distanceToSqr(player);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }
}
