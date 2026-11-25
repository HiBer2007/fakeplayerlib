package top.hibermod.fakeplayer.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import top.hibermod.fakeplayer.menu.FakeInventoryMenu;
import top.hibermod.fakeplayer.net.FakeStatePayload;

/**
 * 假人容器界面：左侧是真正的容器（槽位绑定假人背包，可拖拽 / Q 丢出 / Shift 移动），
 * 右侧是两列操作按钮（全部等价于执行 {@code /player} 指令）。
 *
 * <p>HP / 饱食度 / 快捷栏高亮 / 飞行状态来自服务端的 {@link FakeStatePayload}：
 * 服务端在值变化时下发，界面收到即更新 —— 不轮询、不过期。
 */
public class FakeInventoryScreen extends AbstractContainerScreen<FakeInventoryMenu> {

    private static FakeInventoryScreen CURRENT;

    private EditBox intervalBox;
    private int stateSelected = -1;
    private int stateFood = -1;
    private int stateHealth = -1;
    private boolean stateFlying;
    private boolean autoEat;
    private boolean autoFish;
    private boolean autoTool;
    private boolean autoArmor;
    private boolean sneaking;
    private final java.util.List<Button> toggleButtons = new java.util.ArrayList<>();
    private final java.util.List<String> toggleActions = new java.util.ArrayList<>();

    public FakeInventoryScreen(FakeInventoryMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 210;
        this.imageHeight = 222;
        this.titleLabelX = 8;
        this.titleLabelY = 4;
        this.inventoryLabelX = 32;
        this.inventoryLabelY = 100;
    }

    public static FakeInventoryScreen current() {
        return CURRENT;
    }

    /** 服务端下发的实时状态。 */
    public void applyState(FakeStatePayload p) {
        this.stateSelected = p.selected();
        this.stateFood = p.food();
        this.stateHealth = p.health();
        this.stateFlying = p.flying();
    }

    @Override
    protected void init() {
        super.init();
        CURRENT = this;

        int col1 = this.leftPos + this.imageWidth + 8;
        int col2 = col1 + 108 + 4;
        int w = 108;
        int row = 21;

        int c1y = this.topPos + 2;
        c1y = button(col1, c1y, w, "fakeplayerlib.gui.use_once", "use");
        c1y = button(col1, c1y, w, "fakeplayerlib.gui.use_hold", "use_continuous");
        c1y = button(col1, c1y, w, "fakeplayerlib.gui.use_stop", "use_stop");
        c1y = button(col1, c1y, w, "fakeplayerlib.gui.attack_once", "attack");
        c1y = button(col1, c1y, w, "fakeplayerlib.gui.attack_hold", "attack_continuous");
        c1y = button(col1, c1y, w, "fakeplayerlib.gui.attack_stop", "attack_stop");
        button(col1, c1y, w, "fakeplayerlib.gui.drop", "drop_all");

        int c2y = this.topPos + 2;
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

        for (int i = 1; i <= 9; i++) {
            final int slot = i;
            addRenderableWidget(Button.builder(Component.literal(String.valueOf(i)),
                            b -> runCommand("player " + fakeName() + " hotbar " + slot))
                    .bounds(col2 + (i - 1) * 12, c2y, 11, 18).build());
        }
        c2y += row;
        c2y = button(col2, c2y, w, "fakeplayerlib.gui.fly", "fly");
        c2y = button(col2, c2y, w, "fakeplayerlib.gui.bring", "bring");
        c2y = button(col2, c2y, w, "fakeplayerlib.gui.tp", "tp_to");
        c2y = button(col2, c2y, w, "fakeplayerlib.gui.refresh", "refresh");
        button(col2, c2y, w, "fakeplayerlib.gui.remove", "kill");
    }

    @Override
    public void removed() {
        if (CURRENT == this) {
            CURRENT = null;
        }
        super.removed();
    }

    private int button(int x, int y, int w, String key, String action) {
        addRenderableWidget(Button.builder(Component.translatable(key),
                b -> send(action)).bounds(x, y, w, 18).build());
        return y + 21;
    }

    /** 假人名字就是菜单标题（服务端打开时传入）。 */
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

    /** 有指令的动作走指令（与手敲完全一致），没有指令的走 payload。 */
    private void send(String action) {
        String name = fakeName();
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

        int sel = this.stateSelected >= 0 ? this.stateSelected : this.menu.selectedSlot();
        if (sel >= 0 && sel <= 8) {
            int hx = x + 32 + sel * 18 - 1;
            int hy = y + 76 - 1;
            g.fill(hx, hy, hx + 18, hy + 18, 0x55FFFFFF);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 0x404040, false);
        int hp = this.stateHealth >= 0 ? this.stateHealth : this.menu.healthScaled();
        int food = this.stateFood >= 0 ? this.stateFood : this.menu.foodLevel();
        String info = String.format("HP %d  Food %d%s", hp, food, this.stateFlying ? "  [Flying]" : "");
        g.drawString(this.font, info, this.titleLabelX, this.imageHeight - 12, 0x404040, false);
    }
}
