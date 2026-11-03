package org.allaymc.server.container.impl;

import org.allaymc.api.container.Container;
import org.allaymc.api.container.ContainerType;
import org.allaymc.api.container.interfaces.RecipeContainer;
import org.allaymc.api.item.ItemStack;
import org.allaymc.api.item.interfaces.ItemAirStack;

/**
 * @author daoge_cmd
 */
public abstract class CraftingContainerImpl extends BlockContainerImpl implements RecipeContainer {
    public CraftingContainerImpl(ContainerType<? extends Container> containerType) {
        super(containerType);
    }

    protected ItemStack pickOne(int slot) {
        var item = getItemStack(slot);
        // Container.isEmpty() compares the singleton AIR_STACK by identity.
        // Network/NBT operations may yield a different AIR instance (even with
        // a non-zero count). Recipe inputs must always normalize AIR to count 0.
        if (item.isEmptyOrAir()) {
            return ItemAirStack.AIR_STACK;
        }

        var copy = item.copy(false);
        copy.setCount(1);
        return copy;
    }
}
