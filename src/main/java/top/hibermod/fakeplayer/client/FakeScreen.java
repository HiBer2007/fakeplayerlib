package top.hibermod.fakeplayer.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import top.hibermod.fakeplayer.net.FakeActionPayload;

/**
 * 假人控制面板。
 *
 * <p>用原版 {@link Screen} + {@link Button} 搭，不引第三方 UI 库 ——
 * 面板只有几个按钮，引入 UI 框架的收益抵不上版本耦合的成本。
 */
public class FakeScreen extends Screen {

    private final String name;
    private final String dim;
    private final double x, y, z;
    private final float health, maxHealth;

    public FakeScreen(String json) {
        super(Component.literal("假人控制面板"));
        String n = "?";
        String d = "?";
        double px = 0, py = 0, pz = 0;
        float hp = 0, maxHp = 20;
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            n = o.has("name") ? o.get("name").getAsString() : "?";
            d = o.has("dim") ? o.get("dim").getAsString() : "?";
            px = o.has("x") ? o.get("x").getAsDouble() : 0;
            py = o.has("y") ? o.get("y").getAsDouble() : 0;
            pz = o.has("z") ? o.get("z").getAsDouble() : 0;
            hp = o.has("health") ? o.get("health").getAsFloat() : 0;
            maxHp = o.has("maxHealth") ? o.get("maxHealth").getAsFloat() : 20;
        } catch (Exception ignored) {
            // 面板数据坏了也要能打开，显示占位值即可
        }
        this.name = n;
        this.dim = d;
        this.x = px;
        this.y = py;
        this.z = pz;
        this.health = hp;
        this.maxHealth = maxHp;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 2 - 20;
        int w = 140;
        int h = 20;

        this.addRenderableWidget(Button.builder(Component.literal("传送到假人"),
                b -> act("tp_to", false)).bounds(cx - w - 4, y, w, h).build());
        this.addRenderableWidget(Button.builder(Component.literal("假人传送到我"),
                b -> act("bring", true)).bounds(cx + 4, y, w, h).build());

        this.addRenderableWidget(Button.builder(Component.literal("刷新"),
                b -> act("refresh", true)).bounds(cx - w - 4, y + 26, w, h).build());
        this.addRenderableWidget(Button.builder(Component.literal("移除假人"),
                b -> act("kill", true)).bounds(cx + 4, y + 26, w, h).build());
    }

    private void act(String action, boolean closeAfter) {
        var conn = Minecraft.getInstance().getConnection();
        if (conn != null) {
            conn.send(new ServerboundCustomPayloadPacket(FakeActionPayload.of(action, name)));
        }
        if (closeAfter) {
            this.onClose();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;

        g.drawCenteredString(this.font, "§6假人控制面板", cx, 30, 0xFFFFFF);
        g.drawCenteredString(this.font, "§f" + name, cx, 50, 0xFFFFFF);
        g.drawCenteredString(this.font,
                String.format("§7%s  §f%.1f / %.1f / %.1f", dim, x, y, z),
                cx, 66, 0xAAAAAA);
        g.drawCenteredString(this.font,
                String.format("§7生命 §f%.1f §7/ §f%.1f", health, maxHealth),
                cx, 82, 0xAAAAAA);

        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, "§8按 ESC 关闭", cx, this.height - 28, 0x808080);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
