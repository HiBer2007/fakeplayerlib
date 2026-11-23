package top.hibermod.fakeplayer;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 来自 Velocity 的 limbo 事件通知。
 *
 * <p>通道与代理侧 {@code top.hiberplugin.limbo.HiberLimbo#CHANNEL} 一致：{@code hiberlimbo:main}。
 * 载荷是一段纯文本 {@code action|playerName|serverName}，刻意保持朴素 ——
 * 代理插件与后端模组是两个独立工程，用最简单的文本协议能避免版本耦合。
 */
public record LimboPayload(String body) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<LimboPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath("hiberlimbo", "main"));

    public static final StreamCodec<FriendlyByteBuf, LimboPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeUtf(payload.body(), 256),
                    buf -> new LimboPayload(buf.readUtf(256)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
