package top.hibermod.fakeplayer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import top.hibermod.fakeplayer.FakeConfig;
import top.hibermod.fakeplayer.FakePlayerLib;

import java.util.Map;
import java.util.Set;

/**
 * /fakeplayerlib —— 假人库的全部开关。
 *
 * <p>命令树：
 * <pre>
 * /fakeplayerlib status
 * /fakeplayerlib server on|off
 * /fakeplayerlib distance view &lt;n&gt; | simulation &lt;n&gt; | show
 * /fakeplayerlib client gui on|off
 * /fakeplayerlib behavior list | enable &lt;id&gt; | disable &lt;id&gt;
 * /fakeplayerlib list | spawn &lt;name&gt; | remove &lt;name&gt;
 * /fakeplayerlib config save | reload
 * </pre>
 *
 * <p>所有开关都写进 {@link FakeConfig} 并立即生效，避免"命令改了但运行时没变"。
 */
public final class FakeCommand {

    /** 已知行为 id：新增行为时在这里登记，命令里才有补全。 */
    private static final Set<String> BEHAVIORS =
            Set.of("idle", "look_at_players", "anti_afk", "chunk_loader");

    private FakeCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        var root = Commands.literal("fakeplayerlib").requires(s -> s.hasPermission(2));

        root.then(Commands.literal("status").executes(ctx -> status(ctx.getSource())));

        root.then(Commands.literal("server")
                .then(Commands.literal("on").executes(ctx -> toggle(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> toggle(ctx.getSource(), false))));

        root.then(Commands.literal("distance")
                .then(Commands.literal("view")
                        .then(Commands.argument("chunks", IntegerArgumentType.integer(2, 32))
                                .executes(ctx -> distance(ctx.getSource(), true,
                                        IntegerArgumentType.getInteger(ctx, "chunks")))))
                .then(Commands.literal("simulation")
                        .then(Commands.argument("chunks", IntegerArgumentType.integer(2, 32))
                                .executes(ctx -> distance(ctx.getSource(), false,
                                        IntegerArgumentType.getInteger(ctx, "chunks")))))
                .then(Commands.literal("show").executes(ctx -> status(ctx.getSource()))));

        root.then(Commands.literal("client")
                .then(Commands.literal("gui")
                        .then(Commands.literal("on").executes(ctx -> clientGui(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> clientGui(ctx.getSource(), false)))));

        // 二次确认：警告里的 [确认]/[取消] 点击后等价于这两条命令；控制台也手动输入它们
        root.then(Commands.literal("accept").executes(ctx -> accept(ctx.getSource())));
        root.then(Commands.literal("cancel").executes(ctx -> cancel(ctx.getSource())));

        root.then(Commands.literal("behavior")
                .then(Commands.literal("list").executes(ctx -> behaviorList(ctx.getSource())))
                .then(Commands.literal("enable")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    BEHAVIORS.forEach(b::suggest);
                                    return b.buildFuture();
                                })
                                .executes(ctx -> behaviorSet(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "id"), true))))
                .then(Commands.literal("disable")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    BEHAVIORS.forEach(b::suggest);
                                    return b.buildFuture();
                                })
                                .executes(ctx -> behaviorSet(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "id"), false)))));

        root.then(Commands.literal("list").executes(ctx -> list(ctx.getSource())));
        root.then(Commands.literal("spawn")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> spawn(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));
        root.then(Commands.literal("remove")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> remove(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));

        root.then(Commands.literal("config")
                .then(Commands.literal("save").executes(ctx -> configSave(ctx.getSource())))
                .then(Commands.literal("reload").executes(ctx -> configReload(ctx.getSource())))
                .then(Commands.literal("list").executes(ctx -> configList(ctx.getSource())))
                .then(Commands.literal("set")
                        .then(Commands.argument("key", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    CONFIG_KEYS.forEach(b::suggest);
                                    return b.buildFuture();
                                })
                                .then(Commands.argument("value", StringArgumentType.word())
                                        .executes(ctx -> configSet(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "key"),
                                                StringArgumentType.getString(ctx, "value")))))));

        d.register(root);
    }

    // ---------------------------------------------------------------- 实现

    private static FakeConfig cfg() {
        return FakePlayerLib.get().config();
    }

    private static void feedback(CommandSourceStack src, String text) {
        src.sendSuccess(() -> Component.literal(text), false);
    }

    private static int status(CommandSourceStack src) {
        feedback(src, "§6fakeplayerlib §7" + cfg().describe());
        return 1;
    }

    private static int toggle(CommandSourceStack src, boolean on) {
        cfg().serverEnabled = on;
        feedback(src, on ? "§a假人功能已开启" : "§c假人功能已关闭");
        return 1;
    }

    private static int distance(CommandSourceStack src, boolean view, int chunks) {
        if (view) {
            cfg().viewDistance = chunks;
        } else {
            cfg().simulationDistance = chunks;
        }
        cfg().apply(FakePlayerLib.get());
        feedback(src, "§a" + (view ? "视距" : "模拟距离") + " 已设为 §e" + chunks + " §7区块");
        return 1;
    }

    private static int clientGui(CommandSourceStack src, boolean on) {
        cfg().clientGuiEnabled = on;
        feedback(src, on ? "§a客户端右键面板已开启" : "§c客户端右键面板已关闭");
        return 1;
    }

    private static int accept(CommandSourceStack src) {
        ConfirmManager.Pending p = ConfirmManager.take(src);
        if (p == null) {
            src.sendFailure(Component.literal("§cnothing to confirm"));
            return 0;
        }
        String[] parts = p.action().split(":", 2);
        return PlayerCommand.executePending(src, parts[0], parts.length > 1 ? parts[1] : "");
    }

    private static int cancel(CommandSourceStack src) {
        ConfirmManager.cancel(src);
        feedback(src, "§acancelled");
        return 1;
    }

    private static int behaviorList(CommandSourceStack src) {
        feedback(src, "§6行为（★=启用）:");
        for (String id : BEHAVIORS.stream().sorted().toList()) {
            feedback(src, "  " + (cfg().behavior(id) ? "§a★ " : "§7  ") + id);
        }
        return 1;
    }

    private static int behaviorSet(CommandSourceStack src, String id, boolean on) {
        if (!BEHAVIORS.contains(id)) {
            src.sendFailure(Component.literal("§c未知行为: " + id));
            return 0;
        }
        if (on) {
            cfg().behaviors.add(id);
        } else {
            cfg().behaviors.remove(id);
        }
        feedback(src, (on ? "§a已启用 " : "§c已禁用 ") + id);
        return 1;
    }

    private static int configSave(CommandSourceStack src) {
        cfg().save(src.getServer());
        feedback(src, "§a设置已保存");
        return 1;
    }

    /** 可点选的配置项（顺序即展示顺序）。 */
    private static final java.util.List<String> CONFIG_KEYS = java.util.List.of(
            "serverEnabled", "clientGuiEnabled", "viewDistance", "simulationDistance", "gameMode",
            "denySpawnForPlayedNames", "denyLoginForFakeNames", "guardRealPlayerActions", "allowForce",
            "spawnLevel", "killLevel", "moveLevel", "useLevel", "guiLevel");

    private static final java.util.List<String> GAME_MODES = java.util.List.of(
            "follow", "survival", "creative", "adventure", "spectator");

    /**
     * {@code /fakeplayerlib config list}：逐项列出当前值，每项文字可点击，
     * 点击即执行对应的 {@code config set}（等效命令），并立刻保存 + 热重载。
     */
    private static int configList(CommandSourceStack src) {
        feedback(src, "§6fakeplayerlib 配置 §7(点击数值切换，立即生效)");
        for (String key : CONFIG_KEYS) {
            Object value = readConfig(key);
            if (value == null) {
                continue;
            }
            Object next = nextValue(key, value);
            var label = Component.literal("  §7" + key + " §e[§f" + value + "§e]")
                    .withStyle(s -> s
                            .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                    net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,
                                    "/fakeplayerlib config set " + key + " " + next))
                            .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                    net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("点击改为 " + next))));
            src.sendSuccess(() -> label, false);
        }
        return 1;
    }

    private static Object readConfig(String key) {
        try {
            var f = FakeConfig.class.getField(key);
            return f.get(cfg());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 点击后的下一个值：布尔取反、权限等级 0→2→4→0、距离 -1→8→16→32→-1、模式循环。 */
    private static Object nextValue(String key, Object value) {
        if (value instanceof Boolean b) {
            return !b;
        }
        if (value instanceof String s) {
            int i = GAME_MODES.indexOf(s);
            return GAME_MODES.get((i + 1) % GAME_MODES.size());
        }
        if (value instanceof Integer i) {
            if (key.endsWith("Level")) {
                return switch (i) {
                    case 0 -> 2;
                    case 2 -> 4;
                    default -> 0;
                };
            }
            return switch (i) {
                case -1 -> 8;
                case 8 -> 16;
                case 16 -> 32;
                default -> -1;
            };
        }
        return value;
    }

    private static int configSet(CommandSourceStack src, String key, String raw) {
        if (!CONFIG_KEYS.contains(key)) {
            src.sendFailure(Component.literal("§cunknown config key: " + key));
            return 0;
        }
        try {
            var f = FakeConfig.class.getField(key);
            Class<?> type = f.getType();
            if (type == boolean.class) {
                f.set(cfg(), Boolean.parseBoolean(raw));
            } else if (type == int.class) {
                f.set(cfg(), Integer.parseInt(raw));
            } else {
                f.set(cfg(), raw);
            }
        } catch (Throwable t) {
            src.sendFailure(Component.literal("§cfailed to set " + key + ": " + t));
            return 0;
        }
        // 热重载：立刻写盘并推送到所有活跃假人
        cfg().save(src.getServer());
        cfg().apply(FakePlayerLib.get());
        feedback(src, "§a" + key + " = §e" + readConfig(key) + " §7(已保存并生效)");
        return 1;
    }

    private static int configReload(CommandSourceStack src) {
        FakePlayerLib.get().setConfig(FakeConfig.load(src.getServer()));
        cfg().apply(FakePlayerLib.get());
        feedback(src, "§a设置已重新载入 §7" + cfg().describe());
        return 1;
    }

    private static int spawn(CommandSourceStack src, String name) {
        MinecraftServer server = src.getServer();
        if (!cfg().serverEnabled) {
            src.sendFailure(Component.literal("§c假人功能已关闭（/fakeplayerlib server on）"));
            return 0;
        }
        if (FakePlayerLib.get().find(server, name) != null) {
            src.sendFailure(Component.literal("§c同名假人已存在"));
            return 0;
        }
        var pos = src.getPosition();
        double feetY = top.hibermod.fakeplayer.FakePlayerFactory.feetY(src);
        var level = src.getLevel();
        try {
            ServerPlayer fake = top.hibermod.fakeplayer.FakePlayerFactory.spawn(
                    server, level, name, pos.x, pos.y, pos.z);
            cfg().applyTo(fake);
            src.sendSuccess(() -> Component.literal("§a已生成假人 §e" + name
                    + " §7(" + fake.getUUID() + ")"), true);
            return 1;
        } catch (Exception e) {
            FakePlayerLib.LOGGER.error("[fakeplayerlib] failed to spawn fake player", e);
            src.sendFailure(Component.literal("§c生成失败：" + e));
            return 0;
        }
    }

    private static int remove(CommandSourceStack src, String name) {
        MinecraftServer server = src.getServer();
        ServerPlayer fake = FakePlayerLib.get().find(server, name);
        if (fake == null) {
            src.sendFailure(Component.literal("§c找不到假人 " + name));
            return 0;
        }
        try {
            top.hibermod.fakeplayer.FakePlayerFactory.remove(server, fake);
            src.sendSuccess(() -> Component.literal("§a已移除假人 §e" + name), true);
            return 1;
        } catch (Exception e) {
            FakePlayerLib.LOGGER.error("[fakeplayerlib] failed to remove fake player", e);
            src.sendFailure(Component.literal("§c移除失败：" + e));
            return 0;
        }
    }

    private static int list(CommandSourceStack src) {
        Map<java.util.UUID, FakePlayerLib.Entry> fakes = FakePlayerLib.get().fakes();
        if (fakes.isEmpty()) {
            feedback(src, "§7当前没有假人");
            return 1;
        }
        feedback(src, "§6活跃假人（" + fakes.size() + "）:");
        var server = src.getServer();
        for (FakePlayerLib.Entry e : fakes.values()) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.uuid());
            String where = p == null ? "?" : String.format("%s %.1f/%.1f/%.1f",
                    p.level().dimension().location(), p.getX(), p.getY(), p.getZ());
            feedback(src, "  " + e.name() + "  @ " + where);
        }
        return 1;
    }
}
