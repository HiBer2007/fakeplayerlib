package top.hibermod.fakeplayer.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import top.hibermod.fakeplayer.FakePlayerLib;

/**
 * 客户端接线。
 *
 * <p>只在 {@code Dist.CLIENT} 分支里被引用 ——
 * 这样专用服务端不会去加载任何依赖 {@code net.minecraft.client} 的类。
 */
public final class ClientSetup {

    private ClientSetup() {
    }

    /** 容器界面注册（mod bus）。 */
    public static void registerScreens(net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event) {
        event.register(FakePlayerLib.FAKE_INVENTORY_MENU.get(), FakeInventoryScreen::new);
    }

    /**
     * 纯客户端指令 {@code /fakeplayerlibc}：不需要任何服务端权限。
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
            p.displayClientMessage(Component.literal(
                    on ? "§afakeplayerlib client GUI enabled" : "§cfakeplayerlib client GUI disabled"), false);
        }
        return 1;
    }
}
