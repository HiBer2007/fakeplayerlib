package top.hibermod.fakeplayer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 假人的"客户端输入"模拟。
 *
 * <p>原版玩家的攻击冷却、冲刺、移动意图都由客户端输入驱动：
 * {@code LocalPlayer} 每 tick 推进 {@code attackStrengthTicker}、维护 {@code isSprinting()}、
 * 并把按键状态写进实体。假人没有客户端，这些状态恒为初始值 ——
 * 结果就是 {@code Player.attack} 的冲刺/充能分支永远不成立，打上去没有击退。
 *
 * <p>这里按 {@code LocalPlayer} 的规则把状态补上，然后**调用原版 {@code player.attack()}**，
 * 不再手工施加击退（手工 knockback 是错的，击退要由原版计算）。
 */
public final class FakeInput {

    /** 每个假人的输入状态。 */
    public static final class State {
        public boolean sprint = true;      // 默认冲刺，与 Carpet 的攻击手感一致
        public boolean jump;
        public boolean forward;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    private FakeInput() {
    }

    public static State of(UUID uuid) {
        return STATES.computeIfAbsent(uuid, k -> new State());
    }

    public static void forget(UUID uuid) {
        STATES.remove(uuid);
    }

    /** 每 tick 把输入状态写回实体（对应 LocalPlayer 的输入处理）。 */
    public static void tick(ServerPlayer player) {
        State s = STATES.get(player.getUUID());
        if (s == null) {
            return;
        }
        player.setSprinting(s.sprint);
        player.setShiftKeyDown(false);
        if (s.jump) {
            player.setJumping(true);
        }
    }

    /**
     * 用输入状态执行一次攻击：先置冲刺，再调用原版 {@code attack}，
     * 击退/暴击/横扫全部由原版计算。
     */
    public static void attack(ServerPlayer player, Entity target) {
        State s = of(player.getUUID());
        boolean prev = player.isSprinting();
        player.setSprinting(s.sprint);
        try {
            player.attack(target);
        } finally {
            player.setSprinting(prev);
        }
    }
}
