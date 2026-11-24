package top.hibermod.fakeplayer.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import top.hibermod.fakeplayer.net.FakeActionPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * 假人面板：左侧原生容器样式的背包，右侧操作列表。
 *
 * <p>左半部分用原版 {@code textures/gui/container/inventory.png}（玩家背包容器界面）
 * 作为底板，按原版槽位坐标摆出 36 格背包 + 4 格护甲 + 副手，
 * 内容来自服务端下发的**只读快照**。
 *
 * <p><b>为什么不直接开一个 {@code AbstractContainerScreen}：</b>
 * 背包整理类模组会对真实容器执行"异常槽位移动"，假人没有真实客户端连接、
 * 槽位语义也不完全等同真玩家，一整理就崩。这里只画快照、不参与容器协议，
 * 所有修改走服务端指令通道（{@link FakeActionPayload}）。
 */
public class FakeScreen extends Screen {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/gui/container/inventory.png");

    /** 原版 inventory.png 内的槽位布局（相对 GUI 左上角）。 */
    private static final int INV_LEFT = 8;
    private static final int INV_TOP = 84;
    private static final int HOTBAR_TOP = 142;
    private static final int ARMOR_LEFT = 8;
    private static final int ARMOR_TOP = 8;
    private static final int OFFHAND_X = 77;
    private static final int OFFHAND_Y = 62;

    private final String name;
    private final String dim;
    private final double x;
    private final double y;
    private final double z;
    private final float health;
    private final float maxHealth;
    private final int selected;
    private final int food;
    private net.minecraft.client.gui.components.EditBox intervalBox;
    private final List<Slot> slots = new ArrayList<>();

    private record Slot(int index, String id, int count) {
    }

    public FakeScreen(String json) {
        super(Component.literal("Fake Player"));
        String n = "?";
        String d = "?";
        double px = 0;
        double py = 0;
        double pz = 0;
        float hp = 0;
        float maxHp = 20;
        int sel = 0;
        int foodLevel = 20;
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            n = o.has("name") ? o.get("name").getAsString() : "?";
            d = o.has("dim") ? o.get("dim").getAsString() : "?";
            px = o.has("x") ? o.get("x").getAsDouble() : 0;
            py = o.has("y") ? o.get("y").getAsDouble() : 0;
            pz = o.has("z") ? o.get("z").getAsDouble() : 0;
            hp = o.has("health") ? o.get("health").getAsFloat() : 0;
            maxHp = o.has("maxHealth") ? o.get("maxHealth").getAsFloat() : 20;
            sel = o.has("selected") ? o.get("selected").getAsInt() : 0;
            foodLevel = o.has("food") ? o.get("food").getAsInt() : 20;
            if (o.has("inv") && o.get("inv").isJsonArray()) {
                JsonArray arr = o.getAsJsonArray("inv");
                for (JsonElement el : arr) {
                    JsonObject s = el.getAsJsonObject();
                    slots.add(new Slot(
                            s.has("slot") ? s.get("slot").getAsInt() : 0,
                            s.has("id") ? s.get("id").getAsString() : "",
                            s.has("count") ? s.get("count").getAsInt() : 0));
                }
            }
        } catch (Exception ignored) {
            // 数据坏了也要能打开面板，显示占位值
        }
        this.name = n;
        this.dim = d;
        this.x = px;
        this.y = py;
        this.z = pz;
        this.health = hp;
        this.maxHealth = maxHp;
        this.selected = sel;
        this.food = foodLevel;
    }

    private int guiLeft() {
        return this.width / 2 - 176;
    }

    private int guiTop() {
        return this.height / 2 - 83;
    }

    @Override
    protected void init() {
        int left = guiLeft();
        int top = guiTop();
        int btnX = left + 176 + 10;
        int btnY = top + 2;
        int bw = 108;
        int row = 21;

        // 面板与按钮必须同属一层：作为 Renderable 注册，按加入顺序先画面板、后画按钮。
        // 之前把面板直接画在 Screen#render 里，和 renderables 层不同，会被整层虚化。
        this.addRenderableOnly(new PanelRenderable());

        btnY = button(btnX, btnY, bw, "Use: once", "use");
        btnY = button(btnX, btnY, bw, "Use: hold", "use_continuous");
        btnY = button(btnX, btnY, bw, "Use: stop", "use_stop");
        btnY = button(btnX, btnY, bw, "Attack: once", "attack");
        btnY = button(btnX, btnY, bw, "Attack: hold", "attack_continuous");
        btnY = button(btnX, btnY, bw, "Attack: stop", "attack_stop");

        // 间隔执行：输入框 + 两个按钮，等效于 /player <name> use|attack interval <n>
        intervalBox = new net.minecraft.client.gui.components.EditBox(
                this.font, btnX, btnY, 46, 18, Component.literal("ticks"));
        intervalBox.setValue("20");
        intervalBox.setMaxLength(3);
        addRenderableWidget(intervalBox);
        final int labelY = btnY;
        addRenderableOnly((g, mx, my, pt) -> g.drawString(this.font, "ticks", btnX + 50, labelY + 5, 0xA0A0A0));
        btnY += 21;
        addRenderableWidget(Button.builder(Component.literal("Use interval"),
                b -> runCommand("player " + name + " use interval " + intervalValue()))
                .bounds(btnX, btnY, bw, 18).build());
        btnY += 21;
        addRenderableWidget(Button.builder(Component.literal("Attack interval"),
                b -> runCommand("player " + name + " attack interval " + intervalValue()))
                .bounds(btnX, btnY, bw, 18).build());
        btnY += 21;

        btnY = button(btnX, btnY, bw, "Drop inventory", "drop_all");
        btnY += 2;

        // 快捷栏选择：9 个紧凑格子
        int hbX = btnX;
        for (int i = 1; i <= 9; i++) {
            final int slot = i;
            addRenderableWidget(Button.builder(Component.literal(String.valueOf(i)),
                            b -> send("hotbar|" + name + "|" + slot))
                    .bounds(hbX + (i - 1) * 12, btnY, 12, 16).build());
        }
        btnY += row + 2;

        btnY = button(btnX, btnY, bw, "Bring here", "bring");
        btnY = button(btnX, btnY, bw, "Teleport to", "tp_to");
        btnY = button(btnX, btnY, bw, "Refresh", "refresh");
        button(btnX, btnY, bw, "Remove", "kill");
    }

    private int button(int x, int y, int w, String label, String action) {
        addRenderableWidget(Button.builder(Component.literal(label),
                b -> send(action + "|" + name)).bounds(x, y, w, 18).build());
        return y + 21;
    }

    /**
     * 面板按钮 = 命令。
     *
     * <p>所有按钮都翻译成真正的 {@code /player ...} 指令交给服务端执行（走
     * {@code ClientPacketListener.sendCommand}，即玩家在聊天栏敲命令那条路径），
     * 因此 GUI 与指令的行为、权限、返回值完全一致，不存在两套实现。
     * 只有服务端没有对应指令的辅助动作（bring/tp/refresh）才回退到 payload。
     */
    private void send(String body) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.connection == null) {
            return;
        }
        String cmd = toCommand(body);
        if (cmd != null) {
            mc.player.connection.sendCommand(cmd);
            return;
        }
        var c = mc.getConnection();
        if (c != null) {
            c.send(new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
                    new FakeActionPayload(body)));
        }
    }

    /** 间隔输入框的合法值（1–200 tick，默认 20）。 */
    private int intervalValue() {
        try {
            int v = Integer.parseInt(intervalBox.getValue().trim());
            return Math.max(1, Math.min(200, v));
        } catch (Throwable t) {
            return 20;
        }
    }

    /** 直接以玩家身份执行指令（与聊天栏输入完全一致）。 */
    private void runCommand(String cmd) {
        var mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.sendCommand(cmd);
        }
    }

    /** 面板动作 → 等价指令；返回 null 表示没有对应指令。 */
    private String toCommand(String body) {
        String[] p = body.split("\\|");
        String action = p[0];
        return switch (action) {
            case "use" -> "player " + name + " use once";
            case "use_continuous" -> "player " + name + " use continuous";
            case "use_stop" -> "player " + name + " use stop";
            case "attack" -> "player " + name + " attack once";
            case "attack_continuous" -> "player " + name + " attack continuous";
            case "attack_stop" -> "player " + name + " attack stop";
            case "drop_all" -> "player " + name + " drop all";
            case "hotbar" -> p.length > 2 ? "player " + name + " hotbar " + p[2] : null;
            case "kill" -> "player " + name + " kill";
            default -> null;
        };
    }

    /** 面板与按钮同层的包装：{@code addRenderableOnly} 要求 Renderable + NarratableEntry。 */
    private final class PanelRenderable implements
            net.minecraft.client.gui.components.Renderable,
            net.minecraft.client.gui.narration.NarratableEntry {

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            renderPanel(g);
        }

        @Override
        public net.minecraft.client.gui.narration.NarratableEntry.NarrationPriority narrationPriority() {
            return net.minecraft.client.gui.narration.NarratableEntry.NarrationPriority.NONE;
        }

        @Override
        public void updateNarration(net.minecraft.client.gui.narration.NarrationElementOutput out) {
            // 只读面板，无需朗读
        }
    }

    /** 面板本体：与按钮同层的 renderable（由 init() 注册）。 */
    private void renderPanel(GuiGraphics g) {
        int left = guiLeft();
        int top = guiTop();
        // 遮罩也画在同一层，避免出现"半透明层 + 实体层"的双层虚化
        g.fill(0, 0, this.width, this.height, 0xC0101010);

        // 左侧容器面板：自绘（原版容器底色 + 边框），与右侧按钮处在同一绘制层。
        // 之前用 inventory.png 贴图做底板，实际画面上底板完全不可见，故改为纯绘制。
        g.fill(left - 1, top - 1, left + 177, top + 167, 0xFF000000);      // 外边框
        g.fill(left, top, left + 176, top + 166, 0xFFC6C6C6);               // 容器底色
        g.fill(left, top, left + 176, top + 1, 0xFFFFFFFF);                 // 顶部高光
        g.fill(left, top, left + 1, top + 166, 0xFFFFFFFF);                 // 左侧高光
        g.fill(left + 175, top, left + 176, top + 166, 0xFF555555);         // 右侧阴影
        g.fill(left, top + 165, left + 176, top + 166, 0xFF555555);         // 底部阴影

        // 槽位：主背包 3×9、快捷栏 1×9、护甲 4、副手 1
        for (int r = 0; r < 3; r++) {
            for (int cIdx = 0; cIdx < 9; cIdx++) {
                drawSlot(g, left + INV_LEFT + cIdx * 18, top + INV_TOP + r * 18);
            }
        }
        for (int cIdx = 0; cIdx < 9; cIdx++) {
            drawSlot(g, left + INV_LEFT + cIdx * 18, top + HOTBAR_TOP);
        }
        for (int i = 0; i < 4; i++) {
            drawSlot(g, left + ARMOR_LEFT, top + ARMOR_TOP + i * 18);
        }
        drawSlot(g, left + OFFHAND_X, top + OFFHAND_Y);

        // 物品图标（只读快照）
        for (Slot s : slots) {
            int[] pos = slotPos(s.index());
            if (pos == null || s.count() <= 0) {
                continue;
            }
            ItemStack stack = stackOf(s);
            if (stack.isEmpty()) {
                continue;
            }
            int sx = left + pos[0];
            int sy = top + pos[1];
            g.renderItem(stack, sx, sy);
            g.renderItemDecorations(this.font, stack, sx, sy);
        }

        // 选中槽高亮（快捷栏）
        if (selected >= 0 && selected <= 8) {
            int hx = left + INV_LEFT + selected * 18;
            int hy = top + HOTBAR_TOP;
            g.fill(hx, hy, hx + 16, hy + 16, 0x55000000);
        }

        // 信息
        g.drawString(this.font, "§8" + name, left + 8, top + 6, 0x404040);
        g.drawString(this.font, String.format("§8%.0f %.0f %.0f", x, y, z), left + 92, top + 6, 0x404040);
        g.drawString(this.font, String.format("§8HP %.1f/%.1f  Food %d", health, maxHealth, food),
                left + 8, top + 170, 0x404040);
        g.drawString(this.font, "§8" + dim, left + 8, top + 180, 0x707070);
    }    /** 原版风格的 18×18 槽位。 */
    private void drawSlot(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 18, y + 18, 0xFF8B8B8B);            // 槽底
        g.fill(x, y, x + 18, y + 1, 0xFF373737);             // 上/左边框
        g.fill(x, y, x + 1, y + 18, 0xFF373737);
        g.fill(x, y + 17, x + 18, y + 18, 0xFFFFFFFF);       // 下/右高光
        g.fill(x + 17, y, x + 18, y + 18, 0xFFFFFFFF);
    }

    private ItemStack stackOf(Slot s) {
        try {
            var id = ResourceLocation.tryParse(s.id());
            if (id == null) {
                return ItemStack.EMPTY;
            }
            var item = BuiltInRegistries.ITEM.get(id);
            return new ItemStack(item, s.count());
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 假人背包槽位 → 原版 inventory.png 坐标。
     * 1.21 的 PlayerInventory：0-8 快捷栏，9-35 主背包，36-39 护甲，40 副手。
     */
    private int[] slotPos(int index) {
        if (index >= 0 && index <= 8) {
            return new int[]{INV_LEFT + index * 18, HOTBAR_TOP};
        }
        if (index >= 9 && index <= 35) {
            int i = index - 9;
            return new int[]{INV_LEFT + (i % 9) * 18, INV_TOP + (i / 9) * 18};
        }
        if (index >= 36 && index <= 39) {
            // 护甲槽自上而下：头、胸、腿、脚
            int i = 39 - index;
            return new int[]{ARMOR_LEFT, ARMOR_TOP + i * 18};
        }
        if (index == 40) {
            return new int[]{OFFHAND_X, OFFHAND_Y};
        }
        return null;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
