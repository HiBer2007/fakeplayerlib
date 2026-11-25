package top.hibermod.fakeplayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 每个假人的功能开关与姿态状态（默认全部关闭）。
 *
 * <p>开关是<b>按假人</b>保存的，不是全局配置：{@code /player <name> autoeat on}
 * 只影响这一个假人，GUI 上按钮文字显示的就是它当前的真实状态。
 */
public final class FakeToggles {

    /** 蹲下模式。 */
    public enum Sneak {
        OFF, ONCE, HOLD
    }

    public static final class State {
        public boolean autoEat = false;
        public boolean autoFish = false;
        public boolean autoTool = false;
        public boolean autoArmor = false;
        public Sneak sneak = Sneak.OFF;
    }

    private static final Map<UUID, State> MAP = new HashMap<>();

    private FakeToggles() {
    }

    public static State of(UUID uuid) {
        return MAP.computeIfAbsent(uuid, k -> new State());
    }

    public static State peek(UUID uuid) {
        return MAP.get(uuid);
    }

    public static void forget(UUID uuid) {
        MAP.remove(uuid);
    }
}
