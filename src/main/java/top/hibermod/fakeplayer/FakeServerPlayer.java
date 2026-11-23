package top.hibermod.fakeplayer;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

/**
 * 假人玩家实体。
 *
 * <p>完全照 Carpet 的 {@code EntityPlayerMPFake} 实现：它就是一个普通 {@link ServerPlayer}，
 * 物理、动画、属性、物品、经验全部走原版继承。
 *
 * <p><b>关键在 {@link #tick()}：</b>假人的连接不在服务端网络 tick 循环里，所以
 * {@code ServerGamePacketListenerImpl.tick()} 不会替它调用 {@code doTick()}。
 * Carpet 的做法是在实体的 tick 里补 {@code super.tick()} + {@code this.doTick()}，
 * 并每 10 tick 同步一次位置/区块——这里逐行照搬，这就是"重力/位移与真玩家一致"的原因。
 *
 * <p>额外只有两处：死亡后自动退场（假人没有客户端回执），以及身份判定。
 */
public class FakeServerPlayer extends ServerPlayer {

    public FakeServerPlayer(MinecraftServer server, ServerLevel level,
                            com.mojang.authlib.GameProfile profile, ClientInformation info) {
        super(server, level, profile, info);
    }

    @Override
    public void tick() {
        // 与 Carpet 一致：定期同步位置（chunkSource.move 在本映射下签名不同，故省略）
        if (this.level().getServer().getTickCount() % 10 == 0) {
            this.connection.resetPosition();
        }
        try {
            super.tick();
            this.doTick();
            FakeInput.tick(this);
        } catch (NullPointerException ignored) {
            // 与 Carpet 相同的兜底：某些环境下连接字段缺失会抛 NPE，
            // 吞掉比让整个服务端崩掉好
        }
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        FakePlayerLib lib = FakePlayerLib.get();
        if (lib != null) {
            lib.onFakeDeath(this);
        }
    }

    @Override
    public boolean isSpectator() {
        return false;
    }

    @Override
    public boolean isCreative() {
        return false;
    }
}
