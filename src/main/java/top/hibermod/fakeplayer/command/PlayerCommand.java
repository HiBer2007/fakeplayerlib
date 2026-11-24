package top.hibermod.fakeplayer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import top.hibermod.fakeplayer.FakeAction;
import top.hibermod.fakeplayer.FakeConfig;
import top.hibermod.fakeplayer.FakePlayerLib;

/**
 * {@code /player} —— 与 Carpet 对齐的假人指令族。
 *
 * <pre>
 * /player spawn &lt;name&gt; [force]
 * /player &lt;name&gt; kill | leave
 * /player &lt;name&gt; move &lt;forward|back|left|right|up|down&gt;
 * /player &lt;name&gt; tp &lt;x y z&gt; | tp &lt;player&gt;
 * /player &lt;name&gt; look &lt;yaw&gt; &lt;pitch&gt;
 * /player &lt;name&gt; use    [once|continuous|interval &lt;ticks&gt;|stop]
 * /player &lt;name&gt; attack [once|continuous|interval &lt;ticks&gt;|stop]
 * /player &lt;name&gt; drop   [all|mainhand|slot &lt;n&gt;] [force]
 * /player &lt;name&gt; hotbar &lt;1-9&gt;
 * </pre>
 *
 * <p>权限：等级来自 {@link FakeConfig}（spawn/kill 默认 OP，动作默认玩家）。
 * 保护开关开启时，对"真人登录过的名字/真人玩家"的操作会先发
 * [确认]/[取消] 可点击警告（{@link ConfirmManager}），{@code force} 跳过（仅 OP）。
 */
public final class PlayerCommand {

    private PlayerCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        var root = Commands.literal("player");
        root.then(Commands.literal("spawn")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> spawn(ctx.getSource(), StringArgumentType.getString(ctx, "name"), false))
                        .then(Commands.literal("force")
                                .requires(s -> s.hasPermission(2))
                                .executes(ctx -> spawn(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name"), true)))));

        root.then(Commands.argument("name", StringArgumentType.word())
                .suggests((c, b) -> {
                    FakePlayerLib.get().fakes().values().forEach(e -> b.suggest(e.name()));
                    return b.buildFuture();
                })
                .then(Commands.literal("kill")
                        .requires(s -> perm(s, FakeConfig.Action.KILL))
                        .executes(ctx -> leave(ctx.getSource(), name(ctx), true)))
                .then(Commands.literal("leave")
                        .requires(s -> perm(s, FakeConfig.Action.KILL))
                        .executes(ctx -> leave(ctx.getSource(), name(ctx), false)))
                .then(Commands.literal("move")
                        .requires(s -> perm(s, FakeConfig.Action.MOVE))
                        .then(Commands.literal("forward").executes(c -> move(c.getSource(), name(c), 0)))
                        .then(Commands.literal("back").executes(c -> move(c.getSource(), name(c), 1)))
                        .then(Commands.literal("left").executes(c -> move(c.getSource(), name(c), 2)))
                        .then(Commands.literal("right").executes(c -> move(c.getSource(), name(c), 3)))
                        .then(Commands.literal("up").executes(c -> move(c.getSource(), name(c), 4)))
                        .then(Commands.literal("down").executes(c -> move(c.getSource(), name(c), 5))))
                .then(Commands.literal("tp")
                        .requires(s -> perm(s, FakeConfig.Action.MOVE))
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                .executes(c -> tp(c.getSource(), name(c),
                                                        DoubleArgumentType.getDouble(c, "x"),
                                                        DoubleArgumentType.getDouble(c, "y"),
                                                        DoubleArgumentType.getDouble(c, "z"))))))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(c -> tpTo(c.getSource(), name(c),
                                        EntityArgument.getPlayer(c, "target")))))
                .then(Commands.literal("look")
                        .requires(s -> perm(s, FakeConfig.Action.MOVE))
                        .then(Commands.argument("yaw", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("pitch", DoubleArgumentType.doubleArg())
                                        .executes(c -> look(c.getSource(), name(c),
                                                (float) DoubleArgumentType.getDouble(c, "yaw"),
                                                (float) DoubleArgumentType.getDouble(c, "pitch"))))))
                .then(actionNode("use", true))
                .then(actionNode("attack", false))
                .then(dropNode())
                .then(Commands.literal("hotbar")
                        .requires(s -> perm(s, FakeConfig.Action.USE))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 9))
                                .executes(c -> hotbar(c.getSource(), name(c),
                                        IntegerArgumentType.getInteger(c, "n"))))));
        d.register(root);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> dropNode() {
        return Commands.literal("drop")
                .requires(s -> perm(s, FakeConfig.Action.USE))
                .then(Commands.literal("all")
                        .executes(c -> drop(c.getSource(), name(c), -1, false))
                        .then(Commands.literal("force")
                                .requires(s -> s.hasPermission(2))
                                .executes(c -> drop(c.getSource(), name(c), -1, true))))
                .then(Commands.literal("mainhand").executes(c -> drop(c.getSource(), name(c), -2, false)))
                .then(Commands.literal("slot")
                        .then(Commands.argument("n", IntegerArgumentType.integer(0, 40))
                                .executes(c -> drop(c.getSource(), name(c),
                                        IntegerArgumentType.getInteger(c, "n"), false))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> actionNode(String id, boolean isUse) {
        return Commands.literal(id)
                .requires(s -> perm(s, FakeConfig.Action.USE))
                .executes(c -> setAction(c.getSource(), name(c), isUse, FakeAction.Mode.ONCE, 4))
                .then(Commands.literal("once")
                        .executes(c -> setAction(c.getSource(), name(c), isUse, FakeAction.Mode.ONCE, 4)))
                .then(Commands.literal("continuous")
                        .executes(c -> setAction(c.getSource(), name(c), isUse, FakeAction.Mode.CONTINUOUS, 1)))
                .then(Commands.literal("interval")
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 200))
                                .executes(c -> setAction(c.getSource(), name(c), isUse,
                                        FakeAction.Mode.INTERVAL,
                                        IntegerArgumentType.getInteger(c, "ticks")))))
                .then(Commands.literal("stop")
                        .executes(c -> setAction(c.getSource(), name(c), isUse, null, 4)));
    }

    // ------------------------------------------------------------------ 工具

    private static String name(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "name");
    }

    private static FakeConfig cfg() {
        return FakePlayerLib.get().config();
    }

    private static boolean perm(CommandSourceStack src, FakeConfig.Action action) {
        return src.hasPermission(cfg().level(action));
    }

    private static void ok(CommandSourceStack src, String text) {
        src.sendSuccess(() -> Component.literal(text), false);
    }

    /**
     * 解析操作目标：优先假人；名字对应真人时按 Carpet 语义同样允许，
     * 但 {@code guardRealPlayerActions} 开启时需要确认（force 跳过）。
     */
    private static ServerPlayer target(CommandSourceStack src, String name, boolean force,
                                       String guardAction) {
        ServerPlayer fake = FakePlayerLib.get().find(src.getServer(), name);
        if (fake != null) {
            return fake;
        }
        ServerPlayer real = src.getServer().getPlayerList().getPlayerByName(name);
        if (real == null) {
            src.sendFailure(Component.literal("§cno player named " + name));
            return null;
        }
        if (cfg().guardRealPlayerActions && !force) {
            if (!ConfirmManager.ask(src, "target " + name + " is a REAL player",
                    guardAction + ":" + name)) {
                return null;
            }
            return null;   // 等 accept
        }
        return real;
    }

    // ------------------------------------------------------------------ 动作

    private static int spawn(CommandSourceStack src, String name, boolean force) {
        MinecraftServer server = src.getServer();
        if (!cfg().serverEnabled) {
            src.sendFailure(Component.literal("§cfake players are disabled (/fakeplayerlib server on)"));
            return 0;
        }
        if (FakePlayerLib.get().find(server, name) != null) {
            src.sendFailure(Component.literal("§c" + name + " already exists"));
            return 0;
        }
        // 保护 1：真人登录过的名字不再生成其假人；OP 警告+确认，force 跳过
        if (cfg().denySpawnForPlayedNames && !force
                && server.getProfileCache().get(name).isPresent()) {
            if (!ConfirmManager.ask(src, name + " has joined as a real player before",
                    "spawn:" + name)) {
                return 0;
            }
            return 0;
        }
        var pos = src.getPosition();
        double feetY = top.hibermod.fakeplayer.FakePlayerFactory.feetY(src);
        try {
            ServerPlayer fake = top.hibermod.fakeplayer.FakePlayerFactory.spawn(
                    server, src.getLevel(), name, pos.x, feetY, pos.z);
            applyGameMode(src, fake);
            cfg().applyTo(fake);
            ok(src, "§aspawned §e" + name);
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("§cfailed: " + e));
            return 0;
        }
    }

    /** 默认跟随发起者当前模式；配置为四模式之一时按配置。 */
    private static void applyGameMode(CommandSourceStack src, ServerPlayer fake) {
        String want = cfg().gameMode;
        GameType type;
        if (want == null || want.equalsIgnoreCase("follow")) {
            type = src.getEntity() instanceof ServerPlayer sp
                    ? sp.gameMode.getGameModeForPlayer()
                    : src.getServer().getDefaultGameType();
        } else {
            type = GameType.byName(want.toLowerCase(java.util.Locale.ROOT),
                    src.getServer().getDefaultGameType());
        }
        fake.gameMode.changeGameModeForPlayer(type);
    }

    private static int leave(CommandSourceStack src, String name, boolean killed) {
        ServerPlayer p = FakePlayerLib.get().find(src.getServer(), name);
        if (p == null) {
            src.sendFailure(Component.literal("§cno fake player named " + name));
            return 0;
        }
        FakeAction.clear(p.getUUID());
        if (killed) {
            // kill：走原版死亡（死亡消息、掉落），随后由死亡钩子做原生退场
            p.kill();
        } else {
            // leave：与真人退出等价，走连接的原生断开路径
            // （触发 ServerGamePacketListenerImpl.onDisconnect → removePlayerFromWorld
            //   → 原生「xxx 离开了游戏」广播 + 玩家列表移除）
            top.hibermod.fakeplayer.FakePlayerFactory.disconnect(src.getServer(), p);
        }
        ok(src, "§a" + (killed ? "killed " : "left ") + name);
        return 1;
    }

    private static int move(CommandSourceStack src, String name, int dir) {
        ServerPlayer p = target(src, name, false, "move");
        if (p == null) {
            return 0;
        }
        double d = 1.0;
        double dx = 0;
        double dy = 0;
        double dz = 0;
        switch (dir) {
            case 0 -> {
                dx = -Math.sin(Math.toRadians(p.getYRot())) * d;
                dz = Math.cos(Math.toRadians(p.getYRot())) * d;
            }
            case 1 -> {
                dx = Math.sin(Math.toRadians(p.getYRot())) * d;
                dz = -Math.cos(Math.toRadians(p.getYRot())) * d;
            }
            case 2 -> {
                dx = -Math.cos(Math.toRadians(p.getYRot())) * d;
                dz = -Math.sin(Math.toRadians(p.getYRot())) * d;
            }
            case 3 -> {
                dx = Math.cos(Math.toRadians(p.getYRot())) * d;
                dz = Math.sin(Math.toRadians(p.getYRot())) * d;
            }
            case 4 -> dy = d;
            default -> dy = -d;
        }
        p.teleportTo(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
        ok(src, "§amoved " + name);
        return 1;
    }

    private static int tp(CommandSourceStack src, String name, double x, double y, double z) {
        ServerPlayer p = target(src, name, false, "tp");
        if (p == null) {
            return 0;
        }
        p.teleportTo(x, y, z);
        ok(src, "§ateleported " + name);
        return 1;
    }

    private static int tpTo(CommandSourceStack src, String name, ServerPlayer dest) {
        ServerPlayer p = target(src, name, false, "tp");
        if (p == null) {
            return 0;
        }
        p.teleportTo(dest.getX(), dest.getY(), dest.getZ());
        ok(src, "§ateleported " + name + " to " + dest.getGameProfile().getName());
        return 1;
    }

    private static int look(CommandSourceStack src, String name, float yaw, float pitch) {
        ServerPlayer p = target(src, name, false, "look");
        if (p == null) {
            return 0;
        }
        p.setYRot(yaw);
        p.setXRot(pitch);
        ok(src, "§a" + name + " now looks " + yaw + " / " + pitch);
        return 1;
    }

    private static int setAction(CommandSourceStack src, String name, boolean isUse,
                                 FakeAction.Mode mode, int interval) {
        ServerPlayer p = target(src, name, false, isUse ? "use" : "attack");
        if (p == null) {
            return 0;
        }
        if (isUse) {
            FakeAction.setUse(p.getUUID(), mode, interval);
        } else {
            FakeAction.setAttack(p.getUUID(), mode, interval);
        }
        ok(src, "§a" + name + " " + (isUse ? "use" : "attack") + " = "
                + (mode == null ? "off" : mode.name().toLowerCase()));
        return 1;
    }

    private static int drop(CommandSourceStack src, String name, int slot, boolean force) {
        ServerPlayer p = target(src, name, force, "drop");
        if (p == null) {
            return 0;
        }
        if (slot == -1) {
            p.getInventory().clearContent();
        } else if (slot == -2) {
            // 走原版 DROP_ITEM 动作包（等价于客户端按 Q），而不是直接清手
            top.hibermod.fakeplayer.FakeClient.drop(p, false);
        } else {
            p.getInventory().removeItem(slot, p.getInventory().getItem(slot).getCount());
        }
        ok(src, "§adropped items of " + name);
        return 1;
    }

    private static int hotbar(CommandSourceStack src, String name, int n) {
        ServerPlayer p = target(src, name, false, "hotbar");
        if (p == null) {
            return 0;
        }
        p.getInventory().selected = n - 1;
        ok(src, "§a" + name + " hotbar slot = " + n);
        return 1;
    }

    /** 供 GUI 与保护逻辑复用：来源是否有权操作该目标。 */
    public static boolean mayOperate(Player player, String fakeName) {
        if (player.hasPermissions(2)) {
            return true;
        }
        return fakeName != null && fakeName.equalsIgnoreCase(player.getGameProfile().getName());
    }

    /** {@code /fakeplayerlib accept} 的执行入口：按待确认项重新执行（带 force）。 */
    public static int executePending(CommandSourceStack src, String kind, String name) {
        return switch (kind) {
            case "spawn" -> spawn(src, name, true);
            case "drop" -> drop(src, name, -1, true);
            case "move", "tp", "look", "use", "attack", "hotbar" -> {
                src.sendFailure(Component.literal("§cplease re-run the command with force"));
                yield 0;
            }
            default -> 0;
        };
    }
}
