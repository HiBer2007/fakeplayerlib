package top.hibermod.fakeplayer.menu;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import top.hibermod.fakeplayer.FakePlayerLib;

/**
 * 假人背包容器。
 *
 * <p>槽位<b>直接绑定假人的 {@code PlayerInventory}</b>，所以这是一个真正的容器：
 * 拖拽、Shift 移动、Q 丢出全部由原版 {@link AbstractContainerMenu} 逻辑处理，
 * 改动会直接落到假人身上 —— 和"快照面板"完全不同，不再需要手动刷新，
 * 服务端 {@code ServerPlayer#tick()} 里的 {@code containerMenu.broadcastChanges()}
 * 会把任何一次变化推给客户端。
 *
 * <p>布局（客户端 {@link top.hibermod.fakeplayer.client.FakeInventoryScreen} 与这里一致）：
 * <pre>
 *   护甲(4) x=8  y=18..72      假人主背包 3×9  x=32 y=18
 *   副手    x=8  y=94          假人快捷栏 1×9  x=32 y=76
 *                              观察者背包 3×9  x=32 y=112
 *                              观察者快捷栏  x=32 y=170
 * </pre>
 */
public class FakeInventoryMenu extends AbstractContainerMenu {

    /** 同步给客户端的只读状态：快捷栏选中、饱食度、血量。 */
    public static final int DATA_SIZE = 3;

    private final Container fakeInv;
    private final ContainerData data;
    private final ContainerLevelAccess access;

    /** 客户端构造：只关心槽位数量与坐标。 */
    public FakeInventoryMenu(int id, Inventory viewer) {
        this(id, viewer, null, new SimpleContainerData0());
    }

    /** 服务端构造：槽位绑定真实假人背包。 */
    public FakeInventoryMenu(int id, Inventory viewer, Player fake) {
        this(id, viewer, fake, new FakeData(fake));
    }

    private FakeInventoryMenu(int id, Inventory viewer, Player fake, ContainerData data) {
        super(FakePlayerLib.FAKE_INVENTORY_MENU.get(), id);
        this.data = data;
        this.access = fake == null ? ContainerLevelAccess.NULL
                : ContainerLevelAccess.create(fake.level(), fake.blockPosition());
        Container inv = fake != null ? fake.getInventory() : new SimpleContainer(41);
        this.fakeInv = inv;

        // 护甲：PlayerInventory 36..39 = 脚、腿、胸、头，显示时头在最上
        for (int i = 0; i < 4; i++) {
            this.addSlot(new Slot(inv, 39 - i, 8, 18 + i * 18));
        }
        // 副手
        this.addSlot(new Slot(inv, 40, 8, 94));
        // 假人主背包 9..35（三行）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(inv, 9 + row * 9 + col, 32 + col * 18, 18 + row * 18));
            }
        }
        // 假人快捷栏 0..8
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(inv, col, 32 + col * 18, 76));
        }
        // 观察者背包
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(viewer, 9 + row * 9 + col, 32 + col * 18, 112 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(viewer, col, 32 + col * 18, 170));
        }

        this.addDataSlots(data);
    }

    @Override
    public boolean stillValid(Player player) {
        return true;   // 假人面板允许在任意距离打开（点击假人本身仍受距离校验）
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            int fakeEnd = 41;                       // 0..40 属于假人
            if (index < fakeEnd) {
                // 假人 → 观察者背包
                if (!this.moveItemStackTo(stack, fakeEnd, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(stack, 5, fakeEnd, false)) {
                return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return result;
    }

    /** 快捷栏选中（客户端高亮用）。 */
    public int selectedSlot() {
        return this.data.get(0);
    }

    public int foodLevel() {
        return this.data.get(1);
    }

    public int healthScaled() {
        return this.data.get(2);
    }

    /** 服务端：把假人实时状态喂给同步数据槽。 */
    private record FakeData(Player fake) implements ContainerData {
        @Override
        public int get(int index) {
            if (fake == null) {
                return 0;
            }
            return switch (index) {
                case 0 -> fake.getInventory().selected;
                case 1 -> fake.getFoodData().getFoodLevel();
                default -> (int) fake.getHealth();
            };
        }

        @Override
        public void set(int index, int value) {
            // 只读
        }

        @Override
        public int getCount() {
            return DATA_SIZE;
        }
    }

    private static final class SimpleContainerData0 implements ContainerData {
        @Override
        public int get(int index) {
            return 0;
        }

        @Override
        public void set(int index, int value) {
        }

        @Override
        public int getCount() {
            return DATA_SIZE;
        }
    }
}
