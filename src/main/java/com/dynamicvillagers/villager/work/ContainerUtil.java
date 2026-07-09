package com.dynamicvillagers.villager.work;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

public final class ContainerUtil {

    /** Inserts as much of the stack as fits (merge first, then empty slots). @return the remainder. */
    public static ItemStack insert(Container container, ItemStack stack) {
        for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack existing = container.getItem(i);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack)) {
                int room = Math.min(existing.getMaxStackSize(), container.getMaxStackSize()) - existing.getCount();
                if (room > 0) {
                    int moved = Math.min(room, stack.getCount());
                    existing.grow(moved);
                    stack.shrink(moved);
                    container.setChanged();
                }
            }
        }
        for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
            if (container.getItem(i).isEmpty() && container.canPlaceItem(i, stack)) {
                int moved = Math.min(stack.getCount(), Math.min(stack.getMaxStackSize(), container.getMaxStackSize()));
                container.setItem(i, stack.split(moved));
                container.setChanged();
            }
        }
        return stack;
    }

    private ContainerUtil() {
    }
}
