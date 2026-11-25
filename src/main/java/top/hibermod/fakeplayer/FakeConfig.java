package top.hibermod.fakeplayer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 假人库设置。
 *
 * <p>服务端一份（世界目录下 {@code config/fakeplayerlib/settings.json}），
 * 客户端开关通过 {@code /fakeplayerlib client ...} 下发，不落盘到服务端。
 *
 * <p>所有开关都走这里，命令只是改它、然后调 {@link #apply(FakePlayerLib)} 生效，
 * 避免"命令改了但运行时没变"这种割裂。
 */
public final class FakeConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String REL_PATH = "config/fakeplayerlib/settings.json";

    /** 假人功能总开关（服务端）。 */
    public boolean serverEnabled = true;
    /** 客户端右键假人是否打开面板。 */
    public boolean clientGuiEnabled = true;
    /**
     * 假人视距（区块）。{@code -1} = 跟随服务器设定（服务器 view-distance），
     * 也是默认值：假人不该比服务器允许的范围更小，否则周围不加载区块。
     */
    public int viewDistance = -1;
    /** 假人模拟距离（区块）。{@code -1} = 跟随服务器 simulation-distance。 */
    public int simulationDistance = -1;
    /** 已启用的行为 id。 */
    public Set<String> behaviors = new LinkedHashSet<>(Set.of("idle"));

    // ---- 假人加入游戏时的默认游戏模式：follow = 跟随发起者当前模式 ----
    public String gameMode = "follow";

    // ---- 加入保护（默认全部关闭） ----
    /** 真人登录过的名字不允许再生成其假人。 */
    public boolean denySpawnForPlayedNames = false;
    /** 名字已被假人占用时，拒绝真人登录并提示。 */
    public boolean denyLoginForFakeNames = false;
    /** 禁止对假人执行会影射到真人的操作（如 dropall）。 */
    public boolean guardRealPlayerActions = false;
    /** OP 可以用 force 跳过所有警告。 */
    public boolean allowForce = true;

    // ---- 自动进食 ----
    /** 总开关。 */
    public boolean autoEatEnabled = false;
    /** 自动钓鱼（默认关）。 */
    public boolean autoFishEnabled = false;
    /** 自动换工具（默认关）。 */
    public boolean autoToolEnabled = false;
    /** 自动换护甲（默认关）。 */
    public boolean autoArmorEnabled = false;
    /** 血量百分比阈值：当前血量 ≤ 最大血量的这个百分比就吃（默认 50%）。 */
    public int autoEatHealthPercent = 50;
    /** 饱食度百分比阈值：当前饱食度 ≤ 20 的这个百分比就吃（默认 70% → 14）。 */
    public int autoEatFoodPercent = 70;

    // ---- 指令权限等级（0=普通玩家，2=OP，4=控制台） ----
    public int spawnLevel = 2;
    public int killLevel = 2;
    public int moveLevel = 0;
    public int useLevel = 0;
    public int guiLevel = 0;

    /** 可配置的动作级别。 */
    public enum Action {
        SPAWN(2), KILL(2), MOVE(0), USE(0), GUI(0);
        public final int defaultLevel;

        Action(int defaultLevel) {
            this.defaultLevel = defaultLevel;
        }
    }

    public int level(Action action) {
        return switch (action) {
            case SPAWN -> spawnLevel;
            case KILL -> killLevel;
            case MOVE -> moveLevel;
            case USE -> useLevel;
            case GUI -> guiLevel;
        };
    }

    public static Path fileOf(MinecraftServerRef server) {
        return server.serverDir().resolve(REL_PATH);
    }

    /** 抽象出目录来源，方便在没有服务器实例时（客户端预览）也能读写。 */
    public interface MinecraftServerRef {
        Path serverDir();
    }

    public static FakeConfig load(net.minecraft.server.MinecraftServer server) {
        Path file = server.getServerDirectory().resolve(REL_PATH);
        if (!Files.exists(file)) {
            return new FakeConfig();
        }
        try {
            FakeConfig c = GSON.fromJson(Files.readString(file), FakeConfig.class);
            return c == null ? new FakeConfig() : c;
        } catch (Exception e) {
            FakePlayerLib.LOGGER.warn("[fakeplayerlib] failed to read settings: {}", e.toString());
            return new FakeConfig();
        }
    }

    public void save(net.minecraft.server.MinecraftServer server) {
        try {
            Path file = server.getServerDirectory().resolve(REL_PATH);
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this));
        } catch (IOException e) {
            FakePlayerLib.LOGGER.error("[fakeplayerlib] failed to save settings", e);
        }
    }

    /** 把设置推给所有活跃假人（视距/模拟距离）。 */
    public void apply(FakePlayerLib lib) {
        if (lib == null) {
            return;
        }
        for (FakePlayerLib.Entry e : lib.fakes().values()) {
            net.minecraft.server.MinecraftServer server = lib.server();
            if (server == null) {
                return;
            }
            var p = server.getPlayerList().getPlayer(e.uuid());
            if (p != null) {
                applyTo(p);
            }
        }
    }

    public void applyTo(net.minecraft.server.level.ServerPlayer player) {
        try {
            var server = player.getServer();
            int serverView = server == null ? 10 : server.getPlayerList().getViewDistance();
            // -1 = 跟随服务器设定；否则按配置，但不允许超过服务器上限
            int view = viewDistance < 0 ? serverView : Math.min(viewDistance, serverView);
            view = Math.max(2, Math.min(32, view));

            var info = new net.minecraft.server.level.ClientInformation(
                    player.clientInformation().language(),
                    view,
                    player.clientInformation().chatVisibility(),
                    player.clientInformation().chatColors(),
                    player.clientInformation().modelCustomisation(),
                    player.clientInformation().mainHand(),
                    player.clientInformation().textFilteringEnabled(),
                    player.clientInformation().allowsListing());
            // 原生入口：与客户端上报 ClientInformation 走同一条逻辑，写入 requestedViewDistance
            player.connection.handleClientInformation(
                    new net.minecraft.network.protocol.common.ServerboundClientInformationPacket(info));
        } catch (Throwable t) {
            FakePlayerLib.LOGGER.warn("[fakeplayerlib] failed to apply view distance: {}", t.toString());
        }
    }

    /** 行为总开关：id 是否启用。 */
    public boolean behavior(String id) {
        return behaviors.contains(id);
    }

    public String describe() {
        return "server=" + (serverEnabled ? "on" : "off")
                + " clientGui=" + (clientGuiEnabled ? "on" : "off")
                + " view=" + viewDistance
                + " sim=" + simulationDistance
                + " behaviors=" + behaviors;
    }
}
