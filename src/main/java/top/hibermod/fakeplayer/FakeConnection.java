package top.hibermod.fakeplayer;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.lang.reflect.Field;

/**
 * 一条"没有网络"的连接。
 *
 * <p>{@link net.minecraft.network.Connection} 的方法大多不是 final，因此可以直接覆写成空实现：
 * 所有发往假人的包都被丢弃，永远认为在线，断开请求被忽略。
 *
 * <p>唯一的麻烦是 {@code channel} 字段是私有的，而服务端在多处会调用
 * {@code channel()} 判断连接活性。这里塞一个 {@link EmbeddedChannel} 进去 ——
 * 它不需要事件循环、不占端口，写出的数据留在内部队列里，由模组定时清空。
 */
public class FakeConnection extends Connection {

    private final EmbeddedChannel embedded;

    public FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        this.embedded = new EmbeddedChannel();
        try {
            Field f = Connection.class.getDeclaredField("channel");
            f.setAccessible(true);
            f.set(this, embedded);
        } catch (Exception e) {
            FakePlayerLib.LOGGER.error("[fakeplayerlib] fake connection injection failed; fake players may not tick", e);
        }
    }

    @Override
    public void send(Packet<?> packet) {
        // 丢弃
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener listener) {
        // 丢弃
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
        // 丢弃
    }

    @Override
    public void flushChannel() {
        // 无需刷新
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public void handleDisconnection() {
        // 假人不会因为"连接断开"而被移除
    }

    @Override
    public Channel channel() {
        return embedded;
    }

    /** 清空积压的出站缓冲，避免长时间运行后内存膨胀。 */
    public void drain() {
        try {
            embedded.outboundMessages().clear();
        } catch (Throwable ignored) {
            // EmbeddedChannel 已关闭
        }
    }
}
