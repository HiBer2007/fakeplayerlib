package top.hibermod.fakeplayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 假人的"虚拟客户端"。
 *
 * <p>为什么需要它：原版服务端对玩家的处理是"客户端权威"——
 * {@code ServerGamePacketListenerImpl.tick()} 里的顺序是
 * <pre>
 *   resetPosition();            // firstGood* = 当前位置
 *   player.doTick();            // 服务端物理
 *   player.absMoveTo(firstGood*); // ← 物理结果被丢弃，位置以客户端上报为准
 * </pre>
 * 所以玩家的一切交互都从 <b>Serverbound 包</b> 进入服务端：
 * 移动、攻击（{@code handleInteract}）、放方块（{@code handleUseItemOn}）、
 * 用物品（{@code handleUseItem}）、丢弃（{@code handlePlayerAction}）。
 *
 * <p>这个类就是把假人当作一个"没有任何渲染、只有输入的小客户端"：
 * 每 tick 上报位置，交互时构造原版数据包并交给真正的
 * {@code ServerGamePacketListenerImpl} 处理。
 * 好处是所有模组对这些包处理器的改动（反作弊、领地、权限、事件）都会自然生效，
 * 而不是我们在外面自己调 gameMode 绕过它们。
 */
public final class FakeClient {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private FakeClient() {
    }

    /** 每 tick：像客户端一样上报自身位置，让服务端 firstGood* 与实体位置保持同步。 */
    public static void tick(ServerPlayer player) {
        if (player.connection == null) {
            return;
        }
        try {
            player.connection.handleMovePlayer(new ServerboundMovePlayerPacket.PosRot(
                    player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot(), player.onGround()));
            // 与客户端回执等价：清掉"等待客户端位置确认"状态
            player.connection.resetPosition();
        } catch (Throwable t) {
            FakePlayerLib.LOGGER.debug("[fakeplayerlib] move report failed: {}", t.toString());
        }
    }

    /** 攻击：走原版 {@code handleInteract}，服务端会做距离校验并调用 {@code player.attack}。 */
    public static void attack(ServerPlayer player, Entity target) {
        if (player.connection == null) {
            player.attack(target);
            return;
        }
        player.connection.handleInteract(
                ServerboundInteractPacket.createAttackPacket(target, player.isShiftKeyDown()));
    }

    /**
     * 一次右键：与原版客户端 {@code startUseItem} 的顺序一致 ——
     * 方块 → {@code handleUseItemOn}（放置方块/开箱子/按钮），
     * 实体 → {@code handleInteract}（交互），
     * 否则 → {@code handleUseItem}（食物/投掷物/弓）。
     */
    public static void use(ServerPlayer player) {
        InteractionHand hand = InteractionHand.MAIN_HAND;
        double reach = player.gameMode.isCreative() ? 5.0 : 4.5;
        if (player.connection == null) {
            return;
        }
        try {
            HitResult hit = player.pick(reach, 0.0F, false);
            if (hit.getType() == HitResult.Type.BLOCK) {
                player.connection.handleUseItemOn(new ServerboundUseItemOnPacket(
                        hand, (BlockHitResult) hit, SEQUENCE.incrementAndGet()));
                return;
            }
            Entity target = rayTarget(player, reach);
            if (target != null) {
                player.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(
                        target, player.isShiftKeyDown(), hand));
                return;
            }
            player.connection.handleUseItem(new ServerboundUseItemPacket(
                    hand, SEQUENCE.incrementAndGet(), player.getYRot(), player.getXRot()));
        } catch (Throwable t) {
            FakePlayerLib.LOGGER.warn("[fakeplayerlib] use packet failed: {}", t.toString());
        }
    }

    /** 丢弃手持物：原版 DROP_ITEM 动作包。 */
    public static void drop(ServerPlayer player, boolean all) {
        if (player.connection == null) {
            player.drop(all);
            return;
        }
        player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(
                all ? ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS
                        : ServerboundPlayerActionPacket.Action.DROP_ITEM,
                BlockPos.containing(player.position()), Direction.DOWN));
    }

    /** 与客户端一致的实体射线检测。 */
    public static Entity rayTarget(ServerPlayer player, double reach) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Vec3 end = eye.add(look.scale(reach));
        AABB box = player.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.0);
        var hit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(
                player, eye, end, box,
                e -> !e.isSpectator() && e.isPickable() && e != player,
                reach * reach);
        return hit == null ? null : hit.getEntity();
    }
}
