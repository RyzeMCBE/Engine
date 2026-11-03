package org.allaymc.server.container.impl;

import org.allaymc.api.item.interfaces.ItemAirStack;
import org.allaymc.api.item.recipe.input.CraftingRecipeInput;
import org.allaymc.testutils.AllayTestExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.allaymc.api.item.type.ItemTypes.AIR;
import static org.allaymc.api.item.type.ItemTypes.STONE;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(AllayTestExtension.class)
class CraftingAirInputTest {

    @Test
    void personalGridTreatsNonCanonicalAirAsAnEmptySlot() {
        var grid = new CraftingGridContainerImpl();
        var distinctAir = AIR.createItemStack(1);
        assertNotSame(ItemAirStack.AIR_STACK, distinctAir);
        grid.setItemStack(0, distinctAir, false);
        grid.setItemStack(1, STONE.createItemStack(16), false);

        var input = assertInstanceOf(CraftingRecipeInput.class,
                assertDoesNotThrow(grid::createRecipeInput));
        assertSame(ItemAirStack.AIR_STACK, input.items()[0][0]);
        assertEquals(STONE, input.items()[0][1].getItemType());
        assertEquals(1, input.items()[0][1].getCount());
        assertEquals(16, grid.getItemStack(1).getCount());
    }

    @Test
    void craftingTableTreatsNonCanonicalAirAsAnEmptySlot() {
        var table = new CraftingTableContainerImpl();
        table.setItemStack(8, AIR.createItemStack(1), false);

        var input = assertInstanceOf(CraftingRecipeInput.class,
                assertDoesNotThrow(table::createRecipeInput));
        assertSame(ItemAirStack.AIR_STACK, input.items()[2][2]);
        assertEquals(0, input.items()[2][2].getCount());
    }
}
