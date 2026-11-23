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
    /** 假人视距（区块）。 */
    public int viewDistance = 8;
    /** 假人模拟距离（区块）。 */
    public int simulationDistance = 6;
    /** 已启用的行为 id。 */
    public Set<String> behaviors = new LinkedHashSet<>(Set.of("idle"));

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
            // requestedViewDistance 决定服务端给这个玩家发多少区块；不同版本入口名不同，
            // 用反射兜住，取不到就算了（不影响其它功能）。
            var m = player.getClass().getMethod("setRequestedViewDistance", int.class);
            m.invoke(player, Math.max(2, Math.min(32, viewDistance)));
        } catch (Throwable ignored) {
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
