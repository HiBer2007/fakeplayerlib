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

        btnY = button(btnX, btnY, bw, "Use: once", "use");
        btnY = button(btnX, btnY, bw, "Use: hold", "use_continuous");
        btnY = button(btnX, btnY, bw, "Use: stop", "use_stop");
        btnY = button(btnX, btnY, bw, "Attack: once", "attack");
        btnY = button(btnX, btnY, bw, "Attack: hold", "attack_continuous");
        btnY = button(btnX, btnY, bw, "Attack: stop", "attack_stop");
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

    private void send(String body) {
        var c = Minecraft.getInstance().getConnection();
        if (c != null) {
            c.send(new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
                    new FakeActionPayload(body)));
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);
        int left = guiLeft();
        int top = guiTop();

        // 左侧：原版容器底板（玩家背包界面）
        g.blit(TEXTURE, left, top, 0, 0, 176, 166);

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
            g.fill(hx - 1, hy - 1, hx + 17, hy + 17, 0x66FFFFFF);
        }

        // 信息与状态
        g.drawString(this.font, name, left + 8, top + 6, 0xFFFFFF);
        String pos = String.format("%.0f %.0f %.0f", x, y, z);
        g.drawString(this.font, pos, left + 92, top + 6, 0xA0A0A0);
        g.drawString(this.font, String.format("HP %.1f/%.1f  Food %d", health, maxHealth, food),
                left + 8, top + 166 + 4, 0xA0A0A0);
        g.drawString(this.font, dim, left + 8, top + 166 + 14, 0x707070);

        super.render(g, mouseX, mouseY, partialTick);
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
