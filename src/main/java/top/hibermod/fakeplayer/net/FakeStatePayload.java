package top.hibermod.fakeplayer.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：假人实时状态（快捷栏选中、饱食度、血量、飞行）。
 *
 * <p>容器界面的物品走原版容器协议；这三个值的同步不依赖 {@code ContainerData}
 * （那条路在客户端被丢弃过），改为显式下发，且只在值变化时发送。
 */
public record FakeStatePayload(String name, int selected, int food, int health, boolean flying)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FakeStatePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath("fakeplayerlib", "state"));

    public static final StreamCodec<FriendlyByteBuf, FakeStatePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeUtf(p.name(), 32);
                        buf.writeVarInt(p.selected());
                        buf.writeVarInt(p.food());
                        buf.writeVarInt(p.health());
                        buf.writeBoolean(p.flying());
                    },
                    buf -> new FakeStatePayload(buf.readUtf(32), buf.readVarInt(),
                            buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
