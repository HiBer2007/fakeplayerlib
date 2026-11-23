package top.hibermod.fakeplayer.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import top.hibermod.fakeplayer.net.FakeActionPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * 假人面板：只读背包视图 + 操作按钮。
 *
 * <p><b>为什么不用原版 {@code AbstractContainerScreen}：</b>背包整理类模组会对容器执行
 * 「异常槽位移动」，假人没有真实连接、槽位语义也不完全等同真玩家，一整理就崩。
 * 所以这里只画一份**只读快照**（图标 + 数量），不参与任何容器协议，
 * 物品操作一律走服务端通道（{@link FakeActionPayload}）。
 *
 * <p>布局：左侧 9×4 背包格 + 选中槽高亮，右侧一列操作按钮。
 */
public class FakeScreen extends Screen {

    private static final int SLOT = 18;
    private static final int COLS = 9;
    private static final int ROWS = 4;

    private final String name;
    private final String dim;
    private final double x;
    private final double y;
    private final double z;
    private final float health;
    private final float maxHealth;
    private final int selected;
    private final List<Slot> slots = new ArrayList<>();

    /** 面板里展示的一个物品格（只读）。 */
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
    }

    @Override
    protected void init() {
        int left = this.width / 2 - 190;
        int gridTop = this.height / 2 - 58;
        int btnX = left + COLS * SLOT + 16;
        int btnY = gridTop;
        int bw = 96;
        int row = 22;

        addRenderableWidget(Button.builder(Component.literal("Use: once"),
                b -> send("use|" + name)).bounds(btnX, btnY, bw, 18).build());
        btnY += row;
        addRenderableWidget(Button.builder(Component.literal("Use: hold"),
                b -> send("use_continuous|" + name)).bounds(btnX, btnY, bw, 18).build());
        btnY += row;
        addRenderableWidget(Button.builder(Component.literal("Use: stop"),
                b -> send("use_stop|" + name)).bounds(btnX, btnY, bw, 18).build());
        btnY += row;
        addRenderableWidget(Button.builder(Component.literal("Attack: once"),
                b -> send("attack|" + name)).bounds(btnX, btnY, bw, 18).build());
        btnY += row;
        addRenderableWidget(Button.builder(Component.literal("Attack: hold"),
                b -> send("attack_continuous|" + name)).bounds(btnX, btnY, bw, 18).build());
        btnY += row;
        addRenderableWidget(Button.builder(Component.literal("Attack: stop"),
                b -> send("attack_stop|" + name)).bounds(btnX, btnY, bw, 18).build());
        btnY += row;
        addRenderableWidget(Button.builder(Component.literal("Drop all"),
                b -> send("drop_all|" + name)).bounds(btnX, btnY, bw, 18).build());
        btnY += row + 6;

        for (int i = 1; i <= 9; i++) {
            final int slot = i;
            addRenderableWidget(Button.builder(Component.literal(String.valueOf(i)),
                            b -> send("hotbar|" + name + "|" + slot))
                    .bounds(btnX + (i - 1) * 10, btnY, 10, 16).build());
        }
        btnY += row + 4;
        addRenderableWidget(Button.builder(Component.literal("Bring here"),
                b -> send("bring|" + name)).bounds(btnX, btnY, bw, 18).build());
        btnY += row;
        addRenderableWidget(Button.builder(Component.literal("Teleport to"),
                b -> send("tp_to|" + name)).bounds(btnX, btnY, bw, 18).build());
        btnY += row;
        addRenderableWidget(Button.builder(Component.literal("Refresh"),
                b -> send("refresh|" + name)).bounds(btnX, btnY, bw, 18).build());
        btnY += row;
        addRenderableWidget(Button.builder(Component.literal("Remove"),
                b -> send("kill|" + name)).bounds(btnX, btnY, bw, 18).build());
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
        int left = this.width / 2 - 190;
        int gridTop = this.height / 2 - 58;

        g.drawString(this.font, "Fake Player: " + name, left, gridTop - 26, 0xFFFFFF);
        g.drawString(this.font, String.format("%s  %.1f / %.1f / %.1f   HP %.1f / %.1f",
                dim, x, y, z, health, maxHealth), left, gridTop - 14, 0xA0A0A0);

        for (int r = 0; r < ROWS; r++) {
            for (int cIdx = 0; cIdx < COLS; cIdx++) {
                int sx = left + cIdx * SLOT;
                int sy = gridTop + r * SLOT;
                g.fill(sx, sy, sx + SLOT - 1, sy + SLOT - 1, 0xFF303030);
                g.fill(sx + 1, sy + 1, sx + SLOT - 2, sy + SLOT - 2, 0xFF101010);
            }
        }

        for (Slot s : slots) {
            if (s.index() < 0 || s.index() >= ROWS * COLS || s.count() <= 0) {
                continue;
            }
            int sx = left + (s.index() % COLS) * SLOT;
            int sy = gridTop + (s.index() / COLS) * SLOT;
            try {
                var id = net.minecraft.resources.ResourceLocation.tryParse(s.id());
                if (id != null) {
                    ItemStack stack = new ItemStack(
                            net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id), s.count());
                    g.renderItem(stack, sx + 1, sy + 1);
                    g.renderItemDecorations(this.font, stack, sx + 1, sy + 1);
                }
            } catch (Throwable ignored) {
                // 未知物品就空着
            }
        }

        int selX = left + (selected % COLS) * SLOT;
        int selY = gridTop + (selected / COLS) * SLOT;
        g.fill(selX - 1, selY - 1, selX + SLOT, selY + SLOT, 0x55FFFFFF);

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
