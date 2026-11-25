package top.hibermod.fakeplayer.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：假人实时状态。
 *
 * <p>hp / 饱食度 / 快捷栏选中 / 飞行 / 四个自动开关 / 蹲下，都在值变化时下发。
 * 界面按钮直接显示这些布尔值，所以看到的就是假人的真实状态。
 */
public record FakeStatePayload(String name, int selected, int food, int health, boolean flying,
                               boolean autoEat, boolean autoFish, boolean autoTool,
                               boolean autoArmor, boolean sneaking) implements CustomPacketPayload {

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
                        buf.writeBoolean(p.autoEat());
                        buf.writeBoolean(p.autoFish());
                        buf.writeBoolean(p.autoTool());
                        buf.writeBoolean(p.autoArmor());
                        buf.writeBoolean(p.sneaking());
                    },
                    buf -> new FakeStatePayload(buf.readUtf(32), buf.readVarInt(),
                            buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
                            buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                            buf.readBoolean(), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
