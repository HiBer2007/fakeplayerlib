package top.hibermod.fakeplayer.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import top.hibermod.fakeplayer.menu.FakeInventoryMenu;

/**
 * 假人容器界面：左侧是<b>真正的容器</b>（槽位直接绑定假人背包，可拖拽/丢出/Shift 移动），
 * 右侧是两列操作按钮（全部等价于执行 {@code /player} 指令）。
 *
 * <p>物品同步完全由原版容器协议负责，任何一次背包变化都会立刻到达客户端，
 * 不需要快照刷新，也不会因为"打开太久"而停止更新。
 */
public class FakeInventoryScreen extends AbstractContainerScreen<FakeInventoryMenu> {

    private EditBox intervalBox;

    public FakeInventoryScreen(FakeInventoryMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 210;
        this.imageHeight = 222;
        this.titleLabelX = 8;
        this.titleLabelY = 4;
        this.inventoryLabelX = 32;
        this.inventoryLabelY = 100;
    }

    @Override
    protected void init() {
        super.init();
        int x = this.leftPos + this.imageWidth + 8;
        int y = this.topPos + 2;
        int w = 108;
        int row = 21;

        // 两列：左列动作，右列间隔/其它；恢复原始行距与控件高度
        int col1 = x;
        int col2 = x + w + 4;

        int c1y = y;
        c1y = button(col1, c1y, w, "fakeplayerlib.gui.use_once", "use");
        c1y = button(col1, c1y, w, "fakeplayerlib.gui.use_hold", "use_continuous");
        c1y = button(col1, c1y, w, "fakeplayerlib.gui.use_stop", "use_stop");
        c1y = button(col1, c1y, w, "fakeplayerlib.gui.attack_once", "attack");
        c1y = button(col1, c1y, w, "fakeplayerlib.gui.attack_hold", "attack_continuous");
        c1y = button(col1, c1y, w, "fakeplayerlib.gui.attack_stop", "attack_stop");
        c1y = button(col1, c1y, w, "fakeplayerlib.gui.drop", "drop_all");

        int c2y = y;
        intervalBox = new EditBox(this.font, col2, c2y, 46, 18,
                Component.translatable("fakeplayerlib.gui.ticks"));
        intervalBox.setValue("20");
        intervalBox.setMaxLength(3);
        addRenderableWidget(intervalBox);
        final int ticksY = c2y;
        addRenderableOnly((g, mx, my, pt) -> g.drawString(this.font,
                Component.translatable("fakeplayerlib.gui.ticks").getString(),
                col2 + 50, ticksY + 5, 0xA0A0A0));
        c2y += row;
        addRenderableWidget(Button.builder(Component.translatable("fakeplayerlib.gui.use_interval"),
                b -> runCommand("player " + fakeName() + " use interval " + intervalValue()))
                .bounds(col2, c2y, w, 18).build());
        c2y += row;
        addRenderableWidget(Button.builder(Component.translatable("fakeplayerlib.gui.attack_interval"),
                b -> runCommand("player " + fakeName() + " attack interval " + intervalValue()))
                .bounds(col2, c2y, w, 18).build());
        c2y += row;

        // 快捷栏切换
        for (int i = 1; i <= 9; i++) {
            final int slot = i;
            addRenderableWidget(Button.builder(Component.literal(String.valueOf(i)),
                            b -> runCommand("player " + fakeName() + " hotbar " + slot))
                    .bounds(col2 + (i - 1) * 12, c2y, 11, 18).build());
        }
        c2y += row;
        c2y = button(col2, c2y, w, "fakeplayerlib.gui.fly", "fly");
        c2y = button(col2, c2y, w, "fakeplayerlib.gui.tp", "tp_to");
        c2y = button(col2, c2y, w, "fakeplayerlib.gui.refresh", "refresh");
        button(col2, c2y, w, "fakeplayerlib.gui.remove", "kill");
    }

    private int button(int x, int y, int w, String key, String action) {
        addRenderableWidget(Button.builder(Component.translatable(key),
                b -> send(action)).bounds(x, y, w, 18).build());
        return y + 21;
    }

    /** 假人名字：菜单标题里带着（服务端用 SimpleMenuProvider 传入）。 */
    private String fakeName() {
        return this.getTitle().getString();
    }

    private int intervalValue() {
        try {
            return Math.max(1, Math.min(200, Integer.parseInt(intervalBox.getValue().trim())));
        } catch (Throwable t) {
            return 20;
        }
    }

    private void runCommand(String cmd) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.sendCommand(cmd);
        }
    }

    /** 面板按钮统一走指令；只有没有对应指令的辅助动作走 payload。 */
    private void send(String action) {
        var name = fakeName();
        String cmd = switch (action) {
            case "use" -> "player " + name + " use once";
            case "use_continuous" -> "player " + name + " use continuous";
            case "use_stop" -> "player " + name + " use stop";
            case "attack" -> "player " + name + " attack once";
            case "attack_continuous" -> "player " + name + " attack continuous";
            case "attack_stop" -> "player " + name + " attack stop";
            case "drop_all" -> "player " + name + " drop all";
            case "kill" -> "player " + name + " kill";
            case "fly" -> "player " + name + " fly";
            default -> null;
        };
        if (cmd != null) {
            runCommand(cmd);
            return;
        }
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.send(new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
                    new top.hibermod.fakeplayer.net.FakeActionPayload(action + "|" + name)));
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        int w = this.imageWidth;
        int h = this.imageHeight;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
        g.fill(x, y, x + w, y + h, 0xFFC6C6C6);
        g.fill(x, y, x + w, y + 1, 0xFFFFFFFF);
        g.fill(x, y, x + 1, y + h, 0xFFFFFFFF);
        g.fill(x + w - 1, y, x + w, y + h, 0xFF555555);
        g.fill(x, y + h - 1, x + w, y + h, 0xFF555555);

        // 槽位底纹（与菜单里的坐标一致）
        for (int i = 0; i < this.menu.slots.size(); i++) {
            var s = this.menu.slots.get(i);
            int sx = x + s.x - 1;
            int sy = y + s.y - 1;
            g.fill(sx, sy, sx + 18, sy + 18, 0xFF8B8B8B);
            g.fill(sx, sy, sx + 18, sy + 1, 0xFF373737);
            g.fill(sx, sy, sx + 1, sy + 18, 0xFF373737);
            g.fill(sx, sy + 17, sx + 18, sy + 18, 0xFFFFFFFF);
            g.fill(sx + 17, sy, sx + 18, sy + 18, 0xFFFFFFFF);
        }
        // 当前选中快捷栏高亮
        int sel = this.menu.selectedSlot();
        if (sel >= 0 && sel <= 8) {
            int hx = x + 32 + sel * 18 - 1;
            int hy = y + 76 - 1;
            g.fill(hx, hy, hx + 18, hy + 18, 0x55FFFFFF);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 0x404040, false);
        String info = String.format("HP %d  Food %d", this.menu.healthScaled(), this.menu.foodLevel());
        g.drawString(this.font, info, this.titleLabelX, this.imageHeight - 12, 0x404040, false);
    }
}
