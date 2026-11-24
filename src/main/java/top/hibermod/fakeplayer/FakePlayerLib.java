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

    /** 假人背包容器类型。 */
    public static final net.neoforged.neoforge.registries.DeferredRegister<net.minecraft.world.inventory.MenuType<?>> MENUS =
            net.neoforged.neoforge.registries.DeferredRegister.create(
                    net.minecraft.core.registries.Registries.MENU, MOD_ID);

    public static final java.util.function.Supplier<net.minecraft.world.inventory.MenuType<top.hibermod.fakeplayer.menu.FakeInventoryMenu>> FAKE_INVENTORY_MENU =
            MENUS.register("fake_inventory", () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                    (id, inv, buf) -> {
                        int entityId = buf.readVarInt();
                        net.minecraft.world.entity.player.Player fake =
                                inv.player.level().getEntity(entityId) instanceof net.minecraft.server.level.ServerPlayer sp
                                        ? sp : null;
                        return new top.hibermod.fakeplayer.menu.FakeInventoryMenu(id, inv, fake);
                    }));

    public FakePlayerLib(IEventBus modBus, ModContainer container) {
        instance = this;
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(this::onRegisterCommands);
        // 纯客户端指令（/fakeplayerlibc）只在客户端注册
        if (net.neoforged.fml.loading.FMLEnvironment.dist
                == net.neoforged.api.distmarker.Dist.CLIENT) {
            bus.addListener(top.hibermod.fakeplayer.client.ClientSetup::registerClientCommands);
            modBus.addListener(top.hibermod.fakeplayer.client.ClientSetup::registerScreens);
        }
        bus.addListener(this::onTick);
        bus.addListener(this::onStopping);
        bus.addListener(this::onServerStarted);
        bus.addListener(this::onLivingDeath);
        bus.addListener(this::onPlayerLogin);
        MENUS.register(modBus);
        bus.addListener(top.hibermod.fakeplayer.gui.FakeGuiServer::onEntityInteract);
        modBus.addListener(FakePlayerLib::onRegisterPayloads);
    }

    /** 假人死亡：立即走完退场流程，不让它僵在死亡态。 */
    private void onLivingDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && isFake(p.getUUID())) {
            onFakeDeath(p);
        }
    }

    /**
     * 保护 2：名字已被活跃假人占用时，拒绝真人登录并说明原因。
     *
     * <p>假人自己上线时（placeNewPlayer 也会触发该事件）必须跳过，否则假人永远上不来。
     */
    private void onPlayerLogin(
            net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (!config().denyLoginForFakeNames) {
            return;
        }
        var entity = event.getEntity();
        if (isFake(entity.getUUID())) {
            return;
        }
        String name = entity.getGameProfile().getName();
        if (byName.containsKey(name.toLowerCase(java.util.Locale.ROOT))
                && entity instanceof ServerPlayer sp && sp.connection != null) {
            try {
                sp.connection.disconnect(net.minecraft.network.chat.Component.literal(
                        "This name is currently in use by a fake player on this server"));
            } catch (Throwable t) {
                LOGGER.warn("[fakeplayerlib] failed to reject duplicate login: {}", t.toString());
            }
        }
    }

    private static void onRegisterPayloads(
            net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        LimboLink.register(registrar);
        registrar.playToServer(top.hibermod.fakeplayer.net.FakeActionPayload.TYPE,                top.hibermod.fakeplayer.net.FakeActionPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(
                        () -> top.hibermod.fakeplayer.gui.FakeGuiServer.onAction(payload, ctx)));
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

    /** 是否是本模组托管的假人。 */
    public boolean isFake(UUID uuid) {
        return uuid != null && fakes.containsKey(uuid);
    }

    /**
     * 假人死亡处理。
     *
     * <p>假人没有真实连接，原版死亡流程要等客户端回执才会走完，
     * 于是会永远停在死亡态（看起来就是"卡在死亡画面"）。这里直接把它当成
     * 正常退出：摘出玩家列表、清掉动作包、持久化。
     */
    public void onFakeDeath(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (!isFake(uuid)) {
            return;
        }
        FakeAction.clear(uuid);
        MinecraftServer server = player.server;
        try {
            server.execute(() -> {
                if (server.getPlayerList().getPlayer(uuid) != null) {
                    FakePlayerFactory.remove(server, player);
                }
            });
        } catch (Throwable t) {
            LOGGER.warn("[fakeplayerlib] death cleanup failed for {}: {}", uuid, t.toString());
        }
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
        // /player 对齐 Carpet；/hbfake 已废弃移除
        top.hibermod.fakeplayer.command.PlayerCommand.register(event.getDispatcher());
        top.hibermod.fakeplayer.command.FakeCommand.register(event.getDispatcher());
    }

    private void onTick(ServerTickEvent.Post event) {
        if (fakes.isEmpty()) {
            return;
        }
        long gameTime = event.getServer().overworld() == null ? 0L
                : event.getServer().overworld().getGameTime();
        // 动作包驱动所有持有者（假人 + 被当作操作目标的真人玩家）
        for (java.util.UUID u : FakeAction.active()) {
            ServerPlayer p = event.getServer().getPlayerList().getPlayer(u);
            if (p != null) {
                FakeAction.tick(p, gameTime);
            }
        }
        // 容器界面的同步由原版容器协议完成，不需要任何快照刷新
        // 自动进食对每个活跃假人生效（不受动作包影响）
        for (Entry e : fakes.values()) {
            ServerPlayer p = event.getServer().getPlayerList().getPlayer(e.uuid());
            if (p != null) {
                FakeAutoEat.tick(p);
            }
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
