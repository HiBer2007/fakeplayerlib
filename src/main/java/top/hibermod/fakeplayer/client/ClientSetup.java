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
}
