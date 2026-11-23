package top.hibermod.fakeplayer.command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 需要二次确认的危险操作。
 *
 * <p>不做 GUI 弹窗：服务端发一条警告并附带可点击的 <b>[确认]</b> / <b>[取消]</b> 标签
 * （1.21.1 聊天文本原生支持 {@code ClickEvent.RunCommand}），
 * 点击等同于执行 {@code /fakeplayerlib accept} / {@code /fakeplayerlib cancel}；
 * 控制台没有点击能力，直接手动输入这两条命令。
 *
 * <p>待确认项 30 秒过期，避免悬空状态被误执行。
 */
public final class ConfirmManager {

    /** 控制台来源的统一 key。 */
    private static final UUID CONSOLE = new UUID(0, 0);

    public record Pending(String action, long createdAt) {
    }

    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final long TTL_MS = 30_000L;

    private ConfirmManager() {
    }

    public static UUID key(CommandSourceStack src) {
        return src.getEntity() instanceof ServerPlayer p ? p.getUUID() : CONSOLE;
    }

    /**
     * 发起确认。返回 {@code false} 表示"已发出警告，调用方应当中止本次执行"；
     * 用户点 [确认] 或输入 accept 后再真正执行。
     */
    public static boolean ask(CommandSourceStack src, String reason, String action) {
        UUID key = key(src);
        PENDING.put(key, new Pending(action, System.currentTimeMillis()));
        Component yes = Component.literal("[确认]").setStyle(Style.EMPTY
                .withColor(0x55FF55)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/fakeplayerlib accept"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("执行该操作"))));
        Component no = Component.literal("[取消]").setStyle(Style.EMPTY
                .withColor(0xFF5555)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/fakeplayerlib cancel"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("放弃该操作"))));
        src.sendSuccess(() -> Component.literal("§e" + reason + ". ").append(yes).append(" ").append(no), false);
        return false;
    }

    public static Pending take(CommandSourceStack src) {
        Pending p = PENDING.remove(key(src));
        if (p == null) {
            return null;
        }
        if (System.currentTimeMillis() - p.createdAt() > TTL_MS) {
            return null;
        }
        return p;
    }

    public static void cancel(CommandSourceStack src) {
        PENDING.remove(key(src));
    }
}
