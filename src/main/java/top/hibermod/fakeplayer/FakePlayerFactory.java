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
        UUID uuid = offlineUuid(name);
        GameProfile profile = new GameProfile(uuid, name);
        var cookie = CommonListenerCookie.createInitial(profile, false);

        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        FakeConnection connection = new FakeConnection();

        // 关键时序：必须在 placeNewPlayer 之前登记。
        // placeNewPlayer 会触发 PlayerLoggedInEvent，流量统计模块在那个时机判断"是不是假人"；
        // 如果先上线后登记，这一判断会落到"连接存在"的分支上，假人就被当成真人记账了。
        FakePlayerLib.get().track(uuid, name, connection);

        // placeNewPlayer 内部会建 ServerGamePacketListenerImpl 并挂到 player.connection
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setPos(x, y, z);

        FakePlayerLib.LOGGER.info("[fakeplayerlib] spawned fake player {} @ {} {} {} {}",
                name, level.dimension().location(), x, y, z);
        FakePlayerLib.get().persist();
        return player;
    }

    public static void remove(MinecraftServer server, ServerPlayer player) {
        FakePlayerLib.get().untrack(player.getUUID());
        server.getPlayerList().remove(player);
        FakePlayerLib.LOGGER.info("[fakeplayerlib] removed fake player {}", player.getGameProfile().getName());
        FakePlayerLib.get().persist();
    }
}
