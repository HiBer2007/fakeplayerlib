package top.hibermod.fakeplayer.client;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import top.hibermod.fakeplayer.FakePlayerLib;
import top.hibermod.fakeplayer.net.FakeGuiPayload;

/**
 * 客户端接线。
 *
 * <p>只在 {@code Dist.CLIENT} 分支里被引用 ——
 * 这样专用服务端不会去加载任何依赖 {@code net.minecraft.client} 的类。
 *
 * <p>注册借用公共侧已经建好的 {@link PayloadRegistrar}，
 * 避免同一个 mod 重复调用 {@code event.registrar(...)}。
 */
public final class ClientSetup {

    private ClientSetup() {
    }

    public static void registerClient(PayloadRegistrar registrar) {
        registrar.playToClient(FakeGuiPayload.TYPE, FakeGuiPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() ->
                        Minecraft.getInstance().setScreen(new FakeScreen(payload.json()))));
        FakePlayerLib.LOGGER.info("[fakeplayerlib] client panel ready");
    }

    /**
     * 开发自检钩子：游戏目录下存在 {@code config/fakeplayerlib/preview.flag} 时，
     * 进游戏后直接打开面板（用假数据），方便截图检查布局。正常游玩时该文件不存在，钩子不动作。
     */
    public static void registerPreviewHook() {
        var flag = java.nio.file.Path.of("config/fakeplayerlib/preview.flag");
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Post event) -> {
                    var mc = Minecraft.getInstance();
                    if (mc.screen != null && !(mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen)) {
                        return;
                    }
                    if (!java.nio.file.Files.exists(flag)) {
                        return;
                    }
                    String demo = "{\"name\":\"preview\",\"dim\":\"minecraft:overworld\","
                            + "\"x\":128,\"y\":70,\"z\":-256,\"health\":18.5,\"maxHealth\":20,"
                            + "\"selected\":2,\"food\":17,"
                            + "\"inv\":[{\"slot\":0,\"id\":\"minecraft:stone\",\"count\":64},"
                            + "{\"slot\":2,\"id\":\"minecraft:diamond_sword\",\"count\":1},"
                            + "{\"slot\":9,\"id\":\"minecraft:oak_log\",\"count\":32},"
                            + "{\"slot\":13,\"id\":\"minecraft:golden_apple\",\"count\":3},"
                            + "{\"slot\":36,\"id\":\"minecraft:diamond_boots\",\"count\":1},"
                            + "{\"slot\":40,\"id\":\"minecraft:shield\",\"count\":1}]}";
                    mc.setScreen(new FakeScreen(demo));
                });
    }

    /**
     * 纯客户端指令 {@code /fakeplayerlibc}：不需要任何服务端权限。
     *
     * <p>现在只提供面板开关；服务端相关的开关仍在 {@code /fakeplayerlib}（需 OP）。
     */
    public static void registerClientCommands(
            net.neoforged.neoforge.client.event.RegisterClientCommandsEvent event) {
        var d = event.getDispatcher();
        var root = com.mojang.brigadier.builder.LiteralArgumentBuilder
                .<net.minecraft.commands.CommandSourceStack>literal("fakeplayerlibc");
        root.then(com.mojang.brigadier.builder.LiteralArgumentBuilder
                .<net.minecraft.commands.CommandSourceStack>literal("gui")
                .then(com.mojang.brigadier.builder.LiteralArgumentBuilder
                        .<net.minecraft.commands.CommandSourceStack>literal("on")
                        .executes(ctx -> guiToggle(true)))
                .then(com.mojang.brigadier.builder.LiteralArgumentBuilder
                        .<net.minecraft.commands.CommandSourceStack>literal("off")
                        .executes(ctx -> guiToggle(false))));
        d.register(root);
    }

    /** 客户端本地开关：是否允许右键假人打开面板（只影响本机）。 */
    public static boolean localGuiEnabled = true;

    private static int guiToggle(boolean on) {
        localGuiEnabled = on;
        var p = Minecraft.getInstance().player;
        if (p != null) {
            p.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    on ? "§afakeplayerlib client GUI enabled" : "§cfakeplayerlib client GUI disabled"), false);
        }
        return 1;
    }
}
