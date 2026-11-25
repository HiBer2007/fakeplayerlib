package top.hibermod.fakeplayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 与 tick 速率无关的节奏控制。
 *
 * <p>用 {@code /tick rate} 之类把 tick 提到很高时，按"tick 数"写的冷却会瞬间跑完，
 * 于是假人疯狂攻击/进食/换装备。这里统一用<b>真实时间</b>（毫秒）做节流，
 * 无论服务器是 20tps 还是 2000tps，表现都一致。
 */
public final class FakeTiming {

    private static final Map<String, Long> LAST = new HashMap<>();

    private FakeTiming() {
    }

    private static String key(UUID uuid, String action) {
        return uuid + "|" + action;
    }

    /** 距离上次通过是否已过 {@code ms} 毫秒；通过则刷新时间戳。 */
    public static boolean ready(UUID uuid, String action, long ms) {
        String k = key(uuid, action);
        long now = System.currentTimeMillis();
        Long last = LAST.get(k);
        if (last != null && now - last < ms) {
            return false;
        }
        LAST.put(k, now);
        return true;
    }

    /** 只读查询，不刷新时间戳。 */
    public static boolean elapsed(UUID uuid, String action, long ms) {
        Long last = LAST.get(key(uuid, action));
        return last == null || System.currentTimeMillis() - last >= ms;
    }

    public static void mark(UUID uuid, String action) {
        LAST.put(key(uuid, action), System.currentTimeMillis());
    }

    // ---- 按服务器 tick 计（/tick rate 提高时随之加快，符合"真实 tick"语义） ----

    private static final Map<String, Long> LAST_TICK = new HashMap<>();

    public static boolean readyTicks(UUID uuid, String action, long ticks, long gameTime) {
        String k = key(uuid, action);
        Long last = LAST_TICK.get(k);
        if (last != null && gameTime - last < ticks) {
            return false;
        }
        LAST_TICK.put(k, gameTime);
        return true;
    }

    public static boolean elapsedTicks(UUID uuid, String action, long ticks, long gameTime) {
        Long last = LAST_TICK.get(key(uuid, action));
        return last == null || gameTime - last >= ticks;
    }

    public static void markTicks(UUID uuid, String action, long gameTime) {
        LAST_TICK.put(key(uuid, action), gameTime);
    }

    public static void forget(UUID uuid) {
        String prefix = uuid + "|";
        LAST.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
