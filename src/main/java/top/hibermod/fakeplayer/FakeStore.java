package top.hibermod.fakeplayer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 假人持久化。
 *
 * <p>假人是服务端内存对象，重启就没了 —— 但玩家把它们当成"常驻的挂机号"或
 * "区块加载器"来用，重启后消失会很意外。这里在关服与增删时落盘，开服时按图重建。
 *
 * <p>只存重建必需的最小信息：名字、维度、坐标。血量、背包之类不持久 ——
 * 假人本来也没有真实会话。
 */
public final class FakeStore {

    /** 一条存档记录。 */
    public static final class Entry {
        public String name;
        public String dim;
        public double x;
        public double y;
        public double z;

        public Entry() {
        }

        public Entry(String name, String dim, double x, double y, double z) {
            this.name = name;
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String REL_PATH = "config/fakeplayerlib/fakes.json";
    /** 改名前的旧路径：只用于一次性迁移，迁移成功后不再使用。 */
    private static final String LEGACY_REL_PATH = "config/hibercarpet/fakes.json";

    private FakeStore() {
    }

    private static Path fileOf(MinecraftServer server) {
        return server.getServerDirectory().resolve(REL_PATH);
    }

    public static void save(MinecraftServer server, List<Entry> entries) {
        try {
            Path file = fileOf(server);
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(entries));
        } catch (IOException e) {
            FakePlayerLib.LOGGER.error("[fakeplayerlib] failed to save fake player list", e);
        }
    }

    public static List<Entry> load(MinecraftServer server) {
        Path file = fileOf(server);
        if (!Files.exists(file)) {
            // 兼容改名（hibercarpet -> fakeplayerlib）：旧文件存在就沿用，并顺手迁到新路径
            Path legacy = server.getServerDirectory().resolve(LEGACY_REL_PATH);
            if (Files.exists(legacy)) {
                try {
                    Files.createDirectories(file.getParent());
                    Files.copy(legacy, file);
                    Files.deleteIfExists(legacy);
                    FakePlayerLib.LOGGER.info("[fakeplayerlib] migrated fake player list from {}",
                            LEGACY_REL_PATH);
                } catch (Exception e) {
                    FakePlayerLib.LOGGER.warn("[fakeplayerlib] migration failed, reading legacy file: {}",
                            e.toString());
                    file = legacy;
                }
            }
        }
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            var type = new TypeToken<List<Entry>>() {
            }.getType();
            List<Entry> list = GSON.fromJson(Files.readString(file), type);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            FakePlayerLib.LOGGER.error("[fakeplayerlib] failed to read fake player list", e);
            return List.of();
        }
    }

    /** 按存档重建所有假人。 */
    public static void respawnAll(MinecraftServer server) {
        List<Entry> saved = load(server);
        if (saved.isEmpty()) {
            return;
        }
        int ok = 0;
        FakePlayerLib.get().setRespawning(true);
        try {
        for (Entry e : saved) {
            if (e.name == null || e.name.isBlank()) {
                continue;
            }
            ServerLevel level = levelOf(server, e.dim);
            if (level == null) {
                FakePlayerLib.LOGGER.warn("[fakeplayerlib] dimension {} of fake player {} is not loaded, skipping",
                        e.name, e.dim);
                continue;
            }
            try {
                FakePlayerFactory.spawn(server, level, e.name, e.x, e.y, e.z);
                ok++;
            } catch (Exception ex) {
                FakePlayerLib.LOGGER.error("[fakeplayerlib] failed to recreate fake player {}", e.name, ex);
            }
        }
        } finally {
            FakePlayerLib.get().setRespawning(false);
        }
        FakePlayerLib.LOGGER.info("[fakeplayerlib] recreated {}/{} fake players from save", ok, saved.size());
    }

    /** 把当前活跃假人导出为存档条目。 */
    public static List<Entry> snapshot(MinecraftServer server) {
        List<Entry> list = new ArrayList<>();
        for (FakePlayerLib.Entry e : FakePlayerLib.get().fakes().values()) {
            var player = server.getPlayerList().getPlayer(e.uuid());
            if (player == null) {
                continue;
            }
            list.add(new Entry(e.name(),
                    player.level().dimension().location().toString(),
                    player.getX(), player.getY(), player.getZ()));
        }
        return list;
    }

    private static ServerLevel levelOf(MinecraftServer server, String dimId) {
        if (dimId == null) {
            return server.overworld();
        }
        try {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(dimId));
            return server.getLevel(key);
        } catch (Exception e) {
            return null;
        }
    }
}
