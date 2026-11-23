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
                .then(Commands.literal("reload").executes(ctx -> configReload(ctx.getSource()))));

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
