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

        // 关键时序：必须在 placeNewPlayer 之前登记。
        // placeNewPlayer 会触发 PlayerLoggedInEvent，流量统计模块在那个时机判断"是不是假人"；
        // 如果先上线后登记，这一判断会落到"连接存在"的分支上，假人就被当成真人记账了。
        FakePlayerLib.get().track(uuid, name, connection);

        // placeNewPlayer 内部会建 ServerGamePacketListenerImpl 并挂到 player.connection
        server.getPlayerList().placeNewPlayer(connection, player, cookie);

        // 与 Carpet 的生成步骤对齐（当前版本没有带 Set/boolean 的 teleportTo 重载）
        player.stopRiding();                       // 否则假人会骑在上一个载具上
        player.teleportTo(level, x, feetY, z, player.getYRot(), player.getXRot());
        player.setHealth(20.0F);
        player.revive();
        player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT)
                .setBaseValue(0.6F);

        FakePlayerLib.LOGGER.info("[fakeplayerlib] spawned fake player {} @ {} {} {} {}",
                name, level.dimension().location(), x, y, z);
        FakePlayerLib.get().persist();
        return player;
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
     * {@code ServerGamePacketListenerImpl.removePlayerFromWorld()} 里，
     * 只有 {@code onDisconnect(...)} 会触发它；{@code PlayerList.remove} 不会广播。
     * 因此这里走连接的原生断开路径：广播、`player.disconnect()`、玩家列表移除
     * 全部由原版完成，不手工拼消息。
     */
    public static void disconnect(MinecraftServer server, ServerPlayer player) {
        FakePlayerLib lib = FakePlayerLib.get();
        if (lib != null) {
            lib.untrack(player.getUUID());
        }
        top.hibermod.fakeplayer.FakeInput.forget(player.getUUID());
        try {
            if (player.connection != null) {
                player.connection.disconnect(
                        net.minecraft.network.chat.Component.translatable("multiplayer.disconnect.generic"));
            } else {
                server.getPlayerList().remove(player);
            }
        } catch (Throwable t) {
            FakePlayerLib.LOGGER.warn("[fakeplayerlib] native disconnect failed: {}", t.toString());
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
