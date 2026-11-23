package top.hibermod.fakeplayer;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.hibermod.fakeplayer.command.FakeCommand;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HiBer Carpet —— 只保留 Carpet 里"假人"这一块，不引入它对原版的大量修改。
 *
 * <p>做成独立模组的原因：Carpet 为了各种调试特性改动了原版的 tick 循环、
 * 方块更新、实体跟踪等多个关键路径，全量移植会与现有模组包（Create / Sable / Connector）
 * 产生难以定位的相互作用。这里只借用它的核心思路：
 * <b>用一条无网络连接构造真正的 {@link ServerPlayer}</b>。
 *
 * <p>假人会被登记到 hibertraffic 的过滤表里（反射调用，缺了也不影响运行），
 * 保证计费不会把假人算进去。
 */
@Mod(FakePlayerLib.MOD_ID)
public class FakePlayerLib {

    public static final String MOD_ID = "fakeplayerlib";
    public static final Logger LOGGER = LoggerFactory.getLogger("fakeplayerlib");

    private static FakePlayerLib instance;

    /** 活跃假人：uuid → 记录 */
    private final Map<UUID, Entry> fakes = new ConcurrentHashMap<>();
    /** 名字（小写）→ uuid，供命令查找 */
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();

    public record Entry(UUID uuid, String name, FakeConnection connection) {
    }

    public FakePlayerLib(IEventBus modBus, ModContainer container) {
        instance = this;
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(this::onRegisterCommands);
        bus.addListener(this::onTick);
        bus.addListener(this::onStopping);
        bus.addListener(this::onServerStarted);
        bus.addListener(top.hibermod.fakeplayer.gui.FakeGuiServer::onEntityInteract);
        modBus.addListener(FakePlayerLib::onRegisterPayloads);
    }

    private static void onRegisterPayloads(
            net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        LimboLink.register(registrar);
        registrar.playToServer(top.hibermod.fakeplayer.net.FakeActionPayload.TYPE,
                top.hibermod.fakeplayer.net.FakeActionPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(
                        () -> top.hibermod.fakeplayer.gui.FakeGuiServer.onAction(payload, ctx)));

        // 客户端专属的 S2C 处理器只在客户端注册
        if (net.neoforged.fml.loading.FMLEnvironment.dist
                == net.neoforged.api.distmarker.Dist.CLIENT) {
            top.hibermod.fakeplayer.client.ClientSetup.registerClient(registrar);
        }
    }

    private void onServerStarted(
            net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        this.currentServer = event.getServer();
        // 载入本存档的设置（视距、模拟距离、行为开关等），再按存档重建假人
        this.config = FakeConfig.load(event.getServer());
        FakeStore.respawnAll(event.getServer());
        this.config.apply(this);
    }

    /** 当前生效的设置（不含客户端开关，客户端开关由下发消息维护）。 */
    public FakeConfig config() {
        if (this.config == null) {
            this.config = new FakeConfig();
        }
        return this.config;
    }

    public void setConfig(FakeConfig config) {
        this.config = config == null ? new FakeConfig() : config;
    }

    /** 把当前假人列表写盘。增删与关服时调用。 */
    public void persist() {
        if (this.respawning) {
            return;
        }
        MinecraftServer server = this.currentServer;
        if (server == null) {
            return;
        }
        FakeStore.save(server, FakeStore.snapshot(server));
    }

    /** 重建过程中不要覆盖存档：每次 spawn 都写盘会把列表越写越短。 */
    private volatile boolean respawning;

    public void setRespawning(boolean value) {
        this.respawning = value;
    }

    private volatile MinecraftServer currentServer;
    /** 本存档的设置；在 ServerStartedEvent 里载入。 */
    private volatile FakeConfig config;

    /** 供 LimboLink 在拿不到 source 时兜底。 */
    public MinecraftServer server() {
        return currentServer;
    }

    public static FakePlayerLib get() {
        return instance;
    }

    public Map<UUID, Entry> fakes() {
        return fakes;
    }

    public ServerPlayer find(MinecraftServer server, String name) {
        UUID uuid = byName.get(name.toLowerCase());
        return uuid == null ? null : server.getPlayerList().getPlayer(uuid);
    }

    void track(UUID uuid, String name, FakeConnection connection) {
        fakes.put(uuid, new Entry(uuid, name, connection));
        byName.put(name.toLowerCase(), uuid);
        // 通知流量统计模块：这是假人，不要记账
        registerWithTraffic(uuid);
    }

    void untrack(UUID uuid) {
        Entry e = fakes.remove(uuid);
        if (e != null) {
            byName.remove(e.name().toLowerCase());
        }
        unregisterWithTraffic(uuid);
    }

    // ---------------------------------------------------------- 与 hibertraffic 的软联动

    private static final String TRAFFIC_REGISTRY = "top.hibermod.traffic.FakePlayers";

    private void registerWithTraffic(UUID uuid) {
        try {
            Class<?> c = Class.forName(TRAFFIC_REGISTRY);
            c.getMethod("register", UUID.class).invoke(null, uuid);
        } catch (ClassNotFoundException ignored) {
            // 没装 hibertraffic，正常
        } catch (Exception e) {
            LOGGER.debug("[fakeplayerlib] failed to register with traffic stats: {}", e.toString());
        }
    }

    private void unregisterWithTraffic(UUID uuid) {
        try {
            Class<?> c = Class.forName(TRAFFIC_REGISTRY);
            c.getMethod("unregister", UUID.class).invoke(null, uuid);
        } catch (Exception ignored) {
            // 同上
        }
    }

    // ---------------------------------------------------------- 事件

    private void onRegisterCommands(RegisterCommandsEvent event) {
        FakeCommand.register(event.getDispatcher());
    }

    private void onTick(ServerTickEvent.Post event) {
        if (fakes.isEmpty()) {
            return;
        }
        if (event.getServer().getTickCount() % 200 == 0) {
            for (Entry e : fakes.values()) {
                e.connection().drain();
            }
        }
    }

    private void onStopping(ServerStoppingEvent event) {
        persist();
        fakes.clear();
        byName.clear();
    }
}
