package top.hibermod.fakeplayer.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：打开假人控制面板。
 *
 * <p>载荷是一段 JSON，包含假人的名字、坐标、血量与当前动作。
 * 之所以不在客户端自己查实体数据：客户端未必加载了那个区块，
 * 而且假人的权威状态只在服务端。
 */
public record FakeGuiPayload(String json) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FakeGuiPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath("fakeplayerlib", "gui"));

    public static final StreamCodec<FriendlyByteBuf, FakeGuiPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeUtf(p.json(), 4096),
                    buf -> new FakeGuiPayload(buf.readUtf(4096)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
