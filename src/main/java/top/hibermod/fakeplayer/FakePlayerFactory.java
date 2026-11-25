package top.hibermod.fakeplayer;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 假人创建。
 *
 * <p>走的是服务端自己的 {@code PlayerList.placeNewPlayer}，而不是手工往世界里塞实体。
 * 这样假人拥有完整的玩家生命周期（tick、区块加载、实体追踪），
 * 与 Carpet 的做法一致，只是连接被换成了 {@link FakeConnection}。
 */
public final class FakePlayerFactory {

    private FakePlayerFactory() {
    }

    /** 与离线模式一致的用户名派生 UUID。 */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    public static ServerPlayer spawn(MinecraftServer server, ServerLevel level,
                                     String name, double x, double y, double z) {
        // y 由调用方保证是脚底坐标（见 feetY(CommandSourceStack)）
        double feetY = y;
        UUID uuid = offlineUuid(name);
        GameProfile profile = resolveProfile(server, uuid, name);
        var cookie = CommonListenerCookie.createInitial(profile, false);

        ServerPlayer player = new FakeServerPlayer(server, level, profile,
                ClientInformation.createDefault());
        FakeConnection connection = new FakeConnection();

        // 与 Carpet 一致：**先定位再加入世界**。
        // placeNewPlayer 会把实体按当前坐标塞进 level；ServerPlayer 默认坐标是
        // (0,0,0)/世界出生点，若那里是方块内部、岩浆或虚空，加入瞬间就会受伤甚至死亡。
        // 之后再把血量拉回 20，只会留下"死亡动画卡在第一帧、kill 却认为还活着"的假死状态。
        player.moveTo(x, feetY, z, player.getYRot(), player.getXRot());

        // 关键时序：必须在 placeNewPlayer 之前登记。
        // placeNewPlayer 会触发 PlayerLoggedInEvent，流量统计模块在那个时机判断"是不是假人"；
        // 如果先上线后登记，这一判断会落到"连接存在"的分支上，假人就被当成真人记账了。
        FakePlayerLib.get().track(uuid, name, connection);

        // placeNewPlayer 内部会建 ServerGamePacketListenerImpl 并挂到 player.connection
        server.getPlayerList().placeNewPlayer(connection, player, cookie);

        // 与 Carpet 的生成步骤对齐（当前版本没有带 Set/boolean 的 teleportTo 重载）
        player.stopRiding();                       // 否则假人会骑在上一个载具上
        player.teleportTo(level, x, feetY, z, player.getYRot(), player.getXRot());
        player.setHealth(player.getMaxHealth());
        player.revive();
        player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT)
                .setBaseValue(0.6F);

        // 清掉入场伤害/旧 playerdata 留下的死亡状态：
        // deathTime > 0 而血量 > 0 正是"假死动画反复抽搐"的来源
        resetDeathState(player);

        // 加入流程收尾：placeNewPlayer 会向假人发一次位置同步包，
        // 监听器据此进入"等待客户端回执"状态。假人没有客户端，
        // 这里直接清空该状态（等价于客户端回了一次 AcceptTeleportation）。
        player.connection.resetPosition();

        FakePlayerLib.LOGGER.info("[fakeplayerlib] spawned fake player {} @ {} {} {} {}",
                name, level.dimension().location(), x, y, z);
        FakePlayerLib.get().persist();
        return player;
    }

    /**
     * 清掉死亡残留状态。
     *
     * <p>血量被拉回但 {@code deathTime}/{@code hurtTime} 还留着时，实体就会持续播放
     * 死亡第一帧、看着像抽搐，而 {@code isDeadOrDying()} 为 false —— 也就是"假死"。
     */
    private static void resetDeathState(ServerPlayer player) {
        try {
            player.deathTime = 0;
            player.hurtTime = 0;
            player.hurtDuration = 0;
            player.setHealth(player.getMaxHealth());
            player.clearFire();
            player.setAirSupply(player.getMaxAirSupply());
            player.invulnerableTime = 0;
        } catch (Throwable t) {
            FakePlayerLib.LOGGER.warn("[fakeplayerlib] failed to reset death state: {}", t.toString());
        }
    }

    /**
     * 尽量补齐皮肤：正版账号名可以换到带 textures 的 profile，
     * 离线/无网络时失败并回落到无皮肤（与 Carpet 行为一致）。
     */
    private static GameProfile resolveProfile(MinecraftServer server, UUID uuid, String name) {
        // 1) 服务端的 usercache（正版玩家连过本服，就有带 textures 的档案）——
        //    这是唯一在离线环境下也能拿到皮肤的来源
        try {
            var cached = server.getProfileCache().get(name);
            if (cached.isPresent()) {
                var p = cached.get();
                if (!p.getProperties().isEmpty()) {
                    // 身份仍用离线 UUID（避免和真玩家撞号），只借它的皮肤属性
                    GameProfile merged = new GameProfile(uuid, p.getName());
                    merged.getProperties().putAll(p.getProperties());
                    return merged;
                }
            }
        } catch (Throwable ignored) {
        }
        // 2) 会话服务（需要网络与正版校验）
        try {
            var resolved = server.getSessionService().fetchProfile(uuid, true);
            if (resolved != null && resolved.profile() != null
                    && !resolved.profile().getProperties().isEmpty()) {
                return resolved.profile();
            }
        } catch (Throwable ignored) {
            // 离线环境 / 无网络：保持无皮肤
        }
        return new GameProfile(uuid, name);
    }

    /** 命令来源的脚底 Y：玩家实体用实体坐标，控制台用位置减眼高。 */
    public static double feetY(net.minecraft.commands.CommandSourceStack src) {
        if (src.getEntity() != null) {
            return src.getEntity().getY();
        }
        return src.getPosition().y - 1.62;
    }

    /**
     * 原生退场。
     *
     * <p>1.21.1 的「xxx 离开了游戏」广播写在
     * {@code ServerGamePacketListenerImpl.removePlayerFromWorld()} 里
     * （广播 + {@code player.disconnect()} + {@code playerList.remove()}），
     * 而 {@code PlayerList.remove} 本身不广播 —— 所以必须走监听器的这条原生路径。
     *
     * <p>为什么不直接 {@code connection.disconnect(...)}：
     * 它最终是 {@code server.executeBlocking(connection::handleDisconnection)}，
     * 而假人连接把 {@code handleDisconnection} 留空（避免真实网络 I/O），
     * 事件永远到不了监听器 —— 这正是"死亡变幽灵"的根因。
     *
     * <p>为什么不直接 {@code onDisconnect(...)}：
     * {@code ServerCommonPacketListenerImpl.onDisconnect} 里有
     * {@code if (isSingleplayerOwner()) server.halt(false);}，
     * 而离线单人下假人的名字/UUID 恰好等于房主，会直接把存档服务器关掉。
     * 因此反射调用 {@code removePlayerFromWorld()} 这一支路：
     * 同样是原版代码，但不会误关单人服务器。
     */
    public static void disconnect(MinecraftServer server, ServerPlayer player) {
        FakePlayerLib lib = FakePlayerLib.get();
        if (lib != null) {
            lib.untrack(player.getUUID());
        }
        top.hibermod.fakeplayer.FakeInput.forget(player.getUUID());
        top.hibermod.fakeplayer.FakeAutoEat.forget(player.getUUID());
        top.hibermod.fakeplayer.FakeAutoFish.forget(player.getUUID());
        top.hibermod.fakeplayer.FakeAutoArmor.forget(player.getUUID());
        top.hibermod.fakeplayer.FakeAutoTool.forget(player.getUUID());
        top.hibermod.fakeplayer.FakeToggles.forget(player.getUUID());
        top.hibermod.fakeplayer.FakeTiming.forget(player.getUUID());
        boolean done = false;
        if (player.connection != null) {
            try {
                var m = player.connection.getClass()
                        .getDeclaredMethod("removePlayerFromWorld");
                m.setAccessible(true);
                m.invoke(player.connection);
                done = true;
            } catch (Throwable t) {
                FakePlayerLib.LOGGER.debug(
                        "[fakeplayerlib] removePlayerFromWorld unavailable: {}", t.toString());
            }
            if (!done) {
                try {
                    player.connection.onDisconnect(
                            new net.minecraft.network.DisconnectionDetails(
                                    net.minecraft.network.chat.Component.translatable(
                                            "multiplayer.disconnect.generic")));
                    done = true;
                } catch (Throwable t) {
                    FakePlayerLib.LOGGER.debug(
                            "[fakeplayerlib] onDisconnect fallback failed: {}", t.toString());
                }
            }
        }
        if (!done) {
            server.getPlayerList().remove(player);
        }
        FakePlayerLib.LOGGER.info("[fakeplayerlib] fake player left: {}", player.getGameProfile().getName());
        if (lib != null) {
            lib.persist();
        }
    }

    public static void remove(MinecraftServer server, ServerPlayer player) {
        disconnect(server, player);
    }
}
