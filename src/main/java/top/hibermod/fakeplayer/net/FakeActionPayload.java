package top.hibermod.fakeplayer.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：假人面板上的操作。
 *
 * <p>载荷 {@code action|name}，action 取值：
 * {@code tp_to}（我传送到假人）、{@code bring}（假人传送到我）、
 * {@code kill}（移除假人）、{@code refresh}（刷新面板数据）。
 */
public record FakeActionPayload(String body) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FakeActionPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath("fakeplayerlib", "action"));

    public static final StreamCodec<FriendlyByteBuf, FakeActionPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeUtf(p.body(), 256),
                    buf -> new FakeActionPayload(buf.readUtf(256)));

    public static FakeActionPayload of(String action, String name) {
        return new FakeActionPayload(action + "|" + name);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
