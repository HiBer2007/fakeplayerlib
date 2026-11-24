package top.hibermod.fakeplayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 假人的"虚拟客户端"：把按键动作翻译成原版 Serverbound 包。
 *
 * <p><b>为什么不再每 tick 上报移动包：</b>服务端物理（重力、摔落、击退）是在
 * {@code player.doTick()} 里算出来的；而 {@code handleMovePlayer} 会用包里的坐标
 * 校正实体位置，并顺带清掉 {@code fallDistance}。每 tick 上报等于每 tick 把
 * "服务端算出的下落"覆盖成"上一 tick 的位置"——表现就是落地前一格突然减速、
 * 以及永远没有摔落伤害。所以位置同步改用 {@code connection.resetPosition()}
 * （只更新服务端的 firstGood* 基准，不动实体本身），物理完全交给服务端。
 *
 * <p>交互一律走原版包处理器，模组对这些处理器的改动自然生效：
 * <ul>
 *   <li>左键 = {@code handlePlayerAction(START_DESTROY_BLOCK)} 挖方块 /
 *       {@code handleInteract} 打实体；</li>
 *   <li>右键 = {@code handleUseItemOn}（方块与放置）→ {@code handleInteract}（实体）
 *       → {@code handleUseItem}（物品）；</li>
 *   <li>Q = {@code handlePlayerAction(DROP_ITEM)}。</li>
 * </ul>
 */
public final class FakeClient {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    /** 每个假人正在挖的方块（UUID → BlockPos）。 */
    private static final Map<UUID, BlockPos> BREAKING = new HashMap<>();

    private FakeClient() {
    }

    /** 每 tick：只同步服务端的位置基准，不覆盖物理结果。 */
    public static void tick(ServerPlayer player) {
        if (player.connection == null) {
            return;
        }
        try {
            player.connection.resetPosition();
        } catch (Throwable t) {
            FakePlayerLib.LOGGER.debug("[fakeplayerlib] resetPosition failed: {}", t.toString());
        }
    }

    /**
     * 左键。视线命中方块 → 开始挖（服务端 {@code gameMode.tick()} 会推进破坏进度）；
     * 命中实体 → 攻击；都没命中 → 挥空。
     */
    public static void leftClick(ServerPlayer player) {
        if (player.connection == null) {
            return;
        }
        double reach = player.gameMode.isCreative() ? 5.0 : 4.5;
        HitResult hit = player.pick(reach, 0.0F, false);
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockHitResult block = (BlockHitResult) hit;
            BlockPos pos = block.getBlockPos();
            BlockPos current = BREAKING.get(player.getUUID());
            if (!pos.equals(current)) {
                stopBreaking(player);
                player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                        pos, block.getDirection(), SEQUENCE.incrementAndGet()));
                BREAKING.put(player.getUUID(), pos);
            } else if (player.level().isEmptyBlock(pos)) {
                // 方块已被挖掉：发一次停止，等下一个目标
                stopBreaking(player);
            }
            player.swing(InteractionHand.MAIN_HAND, true);
            return;
        }
        Entity target = rayTarget(player, reach);
        if (target != null) {
            stopBreaking(player);
            // 实体攻击按原版攻击冷却过滤（与真实客户端一致），否则一 tick 打很多次
            if (player.getAttackStrengthScale(0.5F) >= 0.9F) {
                player.connection.handleInteract(
                        ServerboundInteractPacket.createAttackPacket(target, player.isShiftKeyDown()));
            }
            return;
        }
        stopBreaking(player);
        player.swing(InteractionHand.MAIN_HAND, true);
    }

    /** 结束挖掘（松开左键）。 */
    public static void stopBreaking(ServerPlayer player) {
        BlockPos pos = BREAKING.remove(player.getUUID());
        if (pos == null || player.connection == null) {
            return;
        }
        player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                pos, Direction.DOWN, SEQUENCE.incrementAndGet()));
    }

    /** 右键：方块 → 实体 → 物品，顺序与原版客户端一致。 */
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
                player.swing(hand, true);
                return;
            }
            Entity target = rayTarget(player, reach);
            if (target != null) {
                player.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(
                        target, player.isShiftKeyDown(), hand));
                player.swing(hand, true);
                return;
            }
            player.connection.handleUseItem(new ServerboundUseItemPacket(
                    hand, SEQUENCE.incrementAndGet(), player.getYRot(), player.getXRot()));
            player.swing(hand, true);
        } catch (Throwable t) {
            FakePlayerLib.LOGGER.warn("[fakeplayerlib] use packet failed: {}", t.toString());
        }
    }

    /** 松开右键：结束进食/拉弓等"使用中"状态（原版 RELEASE_USE_ITEM）。 */
    public static void useRelease(ServerPlayer player) {
        if (player.connection == null || !player.isUsingItem()) {
            return;
        }
        player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
                BlockPos.containing(player.position()), Direction.DOWN));
    }

    /** Q 键：丢弃手持（单个或整栈）。 */
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
        var eye = player.getEyePosition();
        var look = player.getLookAngle();
        var end = eye.add(look.scale(reach));
        var box = player.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.0);
        var hit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(
                player, eye, end, box,
                e -> !e.isSpectator() && e.isPickable() && e != player,
                reach * reach);
        return hit == null ? null : hit.getEntity();
    }
}
