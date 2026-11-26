package top.hibermod.fakeplayer.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import top.hibermod.fakeplayer.menu.FakeInventoryMenu;
import top.hibermod.fakeplayer.net.FakeStatePayload;

import java.util.ArrayList;
import java.util.List;

/**
 * 假人容器界面。
 *
 * <p>布局按需求分区：
 * <pre>
 *   顶部两行：移动（前/后/左/右，上/下，蹲下 一次/一直/停止）
 *   左列：左右键操作（使用/攻击 × 一次/按住/停止）＋ 间隔刻数输入与“按间隔”按钮
 *   中间：真正的容器（槽位绑定假人背包，可拖拽 / Q 丢出 / Shift 移动）
 *   右列：自动化与状态（自动进食/钓鱼/换工具/换护甲、切换飞行）
 *   底部两行：控制（快捷栏 1–9；丢出全部、传送到我身边、传送到假人、刷新、移除）
 * </pre>
 *
 * <p>HP / 饱食度 / 选中槽 / 各开关状态来自 {@link FakeStatePayload}，服务端值变化即下发。
 */
public class FakeInventoryScreen extends AbstractContainerScreen<FakeInventoryMenu> {

    private static FakeInventoryScreen CURRENT;

    private static final int COL_W = 108;
    private static final int GAP = 6;

    private EditBox intervalBox;
    private int stateSelected = -1;
    private int stateFood = -1;
    private int stateSaturation = -1;
    private int stateHealth = -1;
    private boolean stateFlying;
    private boolean autoEat;
    private boolean autoFish;
    private boolean autoRestock;
    private boolean restockLowDurability = true;
    private boolean autoArmor;

    private final List<Button> toggleButtons = new ArrayList<>();
    private final List<String> toggleActions = new ArrayList<>();

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

    public void applyState(FakeStatePayload p) {
        this.stateSelected = p.selected();
        this.stateFood = p.food();
        this.stateSaturation = p.saturation();
        this.stateHealth = p.health();
        this.stateFlying = p.flying();
        this.autoEat = p.autoEat();
        this.autoFish = p.autoFish();
        this.autoRestock = p.autoTool();
        this.restockLowDurability = p.restockLowDurability();
        this.autoArmor = p.autoArmor();
    }

    @Override
    protected void init() {
        super.init();
        CURRENT = this;

        int panelX = this.leftPos;
        int panelY = this.topPos;
        int panelW = this.imageWidth;
        int panelH = this.imageHeight;

        int originX = panelX - COL_W - GAP;
        int originY = panelY - 2 * 21 - GAP;
        if (originX < 2) {
            originX = 2;
        }
        if (originY < 2) {
            originY = 2;
        }

        // ---- 顶部两行：移动（整行居中，按钮加宽） ----
        int contentLeft = originX;
        int contentWidth = COL_W + GAP + panelW;
        int w4 = (contentWidth - 3 * 2) / 4;
        int row1 = originY;
        int row1X = contentLeft + (contentWidth - (w4 * 4 + 3 * 2)) / 2;
        buttonText(row1X, row1, w4, "fakeplayerlib.gui.move_fwd", "move forward");
        buttonText(row1X + w4 + 2, row1, w4, "fakeplayerlib.gui.move_back", "move back");
        buttonText(row1X + (w4 + 2) * 2, row1, w4, "fakeplayerlib.gui.move_left", "move left");
        buttonText(row1X + (w4 + 2) * 3, row1, w4, "fakeplayerlib.gui.move_right", "move right");
        int row2 = row1 + 21;
        int w5 = (contentWidth - 4 * 2) / 5;
        int row2X = contentLeft + (contentWidth - (w5 * 5 + 4 * 2)) / 2;
        buttonText(row2X, row2, w5, "fakeplayerlib.gui.move_up", "move up");
        buttonText(row2X + w5 + 2, row2, w5, "fakeplayerlib.gui.move_down", "move down");
        buttonText(row2X + (w5 + 2) * 2, row2, w5, "fakeplayerlib.gui.sneak_once", "sneak once");
        buttonText(row2X + (w5 + 2) * 3, row2, w5, "fakeplayerlib.gui.sneak_hold", "sneak hold");
        buttonText(row2X + (w5 + 2) * 4, row2, w5, "fakeplayerlib.gui.sneak_stop", "sneak stop");

        // ---- 左列：左右键操作 + 间隔 ----
        int lx = panelX - COL_W - GAP;
        int ly = panelY;
        ly = buttonText(lx, ly, COL_W, "fakeplayerlib.gui.use_once", "use once");
        ly = buttonText(lx, ly, COL_W, "fakeplayerlib.gui.use_hold", "use continuous");
        ly = buttonText(lx, ly, COL_W, "fakeplayerlib.gui.use_stop", "use stop");
        ly = buttonText(lx, ly, COL_W, "fakeplayerlib.gui.attack_once", "attack once");
        ly = buttonText(lx, ly, COL_W, "fakeplayerlib.gui.attack_hold", "attack continuous");
        ly = buttonText(lx, ly, COL_W, "fakeplayerlib.gui.attack_stop", "attack stop");

        intervalBox = new EditBox(this.font, lx, ly, 46, 18,
                Component.translatable("fakeplayerlib.gui.ticks"));
        intervalBox.setValue("20");
        intervalBox.setMaxLength(3);
        addRenderableWidget(intervalBox);
        final int ty = ly;
        addRenderableOnly((g, mx, my, pt) -> g.drawString(this.font,
                Component.translatable("fakeplayerlib.gui.ticks").getString(), lx + 50, ty + 5, 0x404040));
        ly += 21;
        ly = buttonText(lx, ly, COL_W, "fakeplayerlib.gui.use_interval",
                "use interval " + intervalValue());
        buttonText(lx, ly, COL_W, "fakeplayerlib.gui.attack_interval",
                "attack interval " + intervalValue());

        // ---- 右列：自动化与状态 ----
        int rx = panelX + panelW + GAP;
        int ry = panelY;
        ry = toggleButton(rx, ry, COL_W, "autoeat");
        ry = toggleButton(rx, ry, COL_W, "autofish");
        ry = toggleButton(rx, ry, COL_W, "autotool");
        ry = toggleButton(rx, ry, COL_W, "autoarmor");
        toggleFly = Button.builder(Component.translatable("fakeplayerlib.gui.fly"),
                        b -> runCommand("player " + fakeName() + " fly"))
                .bounds(rx, ry, COL_W, 18).build();
        addRenderableWidget(toggleFly);
        ry += 21;
        // 低耐久补货：全局配置，点击立即取反（先本地翻转，指令随后同步服务端状态）
        toggleLowDurability = Button.builder(Component.empty(), b -> {
            restockLowDurability = !restockLowDurability;
            runCommand("fakeplayerlib config set restockLowDurability " + restockLowDurability);
        }).bounds(rx, ry, COL_W, 18).build();
        addRenderableWidget(toggleLowDurability);

        // ---- 底部两行：控制（整行居中） ----
        int by = panelY + panelH + GAP;
        int hbWidth = 9 * 13;
        int hbX = contentLeft + (contentWidth - hbWidth) / 2;
        for (int i = 1; i <= 9; i++) {
            final int slot = i;
            addRenderableWidget(Button.builder(Component.literal(String.valueOf(i)),
                            b -> runCommand("player " + fakeName() + " hotbar " + slot))
                    .bounds(hbX + (i - 1) * 13, by, 12, 18).build());
        }
        int by2 = by + 21;
        int cw = (contentWidth - 4 * 2) / 5;
        int cx = contentLeft + (contentWidth - (cw * 5 + 4 * 2)) / 2;
        buttonText(cx, by2, cw, "fakeplayerlib.gui.drop", "drop all");
        sendButton(cx + cw + 2, by2, cw, "fakeplayerlib.gui.bring", "bring");
        sendButton(cx + (cw + 2) * 2, by2, cw, "fakeplayerlib.gui.tp", "tp_to");
        sendButton(cx + (cw + 2) * 3, by2, cw, "fakeplayerlib.gui.refresh", "refresh");
        sendButton(cx + (cw + 2) * 4, by2, cw, "fakeplayerlib.gui.remove", "kill");
    }

    private Button toggleFly;
    private Button toggleLowDurability;

    private int buttonText(int x, int y, int w, String key, String commandTail) {
        addRenderableWidget(Button.builder(Component.translatable(key),
                        b -> runCommand("player " + fakeName() + " " + commandTail))
                .bounds(x, y, w, 18).build());
        return y + 21;
    }

    private void sendButton(int x, int y, int w, String key, String action) {
        addRenderableWidget(Button.builder(Component.translatable(key),
                        b -> send(action)).bounds(x, y, w, 18).build());
    }

    private int toggleButton(int x, int y, int w, String action) {
        Button b = Button.builder(Component.translatable("fakeplayerlib.gui." + action),
                        btn -> runCommand("player " + fakeName() + " " + action))
                .bounds(x, y, w, 18).build();
        addRenderableWidget(b);
        toggleButtons.add(b);
        toggleActions.add(action);
        return y + 21;
    }

    @Override
    public void removed() {
        if (CURRENT == this) {
            CURRENT = null;
        }
        super.removed();
    }

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

    private void send(String action) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.send(new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
                    new top.hibermod.fakeplayer.net.FakeActionPayload(action + "|" + fakeName())));
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        for (int i = 0; i < toggleButtons.size(); i++) {
            boolean state = switch (toggleActions.get(i)) {
                case "autoeat" -> autoEat;
                case "autofish" -> autoFish;
                case "autotool", "autorest" -> autoRestock;
                default -> autoArmor;
            };
            String base = Component.translatable("fakeplayerlib.gui." + toggleActions.get(i)).getString();
            toggleButtons.get(i).setMessage(Component.literal(base + ": " + (state ? "§aON" : "§cOFF")));
        }
        if (toggleFly != null) {
            String base = Component.translatable("fakeplayerlib.gui.fly").getString();
            toggleFly.setMessage(Component.literal(base + (stateFlying ? " §a[ON]" : " §c[OFF]")));
        }
        if (toggleLowDurability != null) {
            String base = Component.translatable("fakeplayerlib.gui.low_durability").getString();
            toggleLowDurability.setMessage(Component.literal(
                    base + ": " + (restockLowDurability ? "§aON" : "§cOFF")));
        }
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        int w = this.imageWidth;
        int h = this.imageHeight;

        // 背景遮罩 + 容器面板（与按钮同一次绘制，不额外分层）
        g.fill(0, 0, this.width, this.height, 0x90000000);
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
        String sat = this.stateSaturation >= 0
                ? String.format("%.1f", this.stateSaturation / 10.0F) : "-";
        String info = String.format("HP %d  Food %d  Saturation %s%s", hp, food, sat,
                stateFlying ? "  [Flying]" : "");
        g.drawString(this.font, info, this.titleLabelX, this.imageHeight - 12, 0x404040, false);
    }
}
