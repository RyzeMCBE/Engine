package org.allaymc.server.block.component.fire;

import org.allaymc.api.block.property.type.BlockPropertyTypes;
import org.allaymc.api.block.type.BlockTypes;
import org.allaymc.testutils.AllayTestExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(AllayTestExtension.class)
class BlockFireBaseComponentImplTest {

    @Test
    void spreadAndBurnFireStatesIncludeAllConnectionProperties() {
        var defaultState = BlockTypes.FIRE.getDefaultState();

        for (int age = 0; age <= BlockPropertyTypes.AGE_16.getMax(); age++) {
            var state = BlockFireBaseComponentImpl.fireStateWithAge(age);
            assertNotNull(state, "Fire state must exist for age " + age);
            assertEquals(age, state.getPropertyValue(BlockPropertyTypes.AGE_16));
            assertEquals(defaultState.getPropertyValues().keySet(), state.getPropertyValues().keySet());

            for (var property : defaultState.getPropertyValues().entrySet()) {
                if (property.getKey() == BlockPropertyTypes.AGE_16) {
                    continue;
                }
                assertEquals(property.getValue(), state.getPropertyValues().get(property.getKey()));
            }
        }
    }
}
