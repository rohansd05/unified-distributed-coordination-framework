package com.udcf.web.dto;

import com.udcf.core.module.ExperimentModule;
import com.udcf.core.module.ModuleStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the module wire form used by the sidebar. */
class ModuleDtoTest {

    @Test
    @DisplayName("from maps id, lab number, title and status name")
    void moduleMapping() {
        ExperimentModule module = new ExperimentModule() {
            @Override
            public String id() {
                return "election";
            }

            @Override
            public int labNumber() {
                return 4;
            }

            @Override
            public String title() {
                return "Bully and Ring Election";
            }

            @Override
            public ModuleStatus status() {
                return ModuleStatus.BUSY;
            }

            @Override
            public void reset() {
            }
        };

        assertThat(ModuleDto.from(module))
                .isEqualTo(new ModuleDto("election", 4, "Bully and Ring Election", "BUSY"));
    }
}
