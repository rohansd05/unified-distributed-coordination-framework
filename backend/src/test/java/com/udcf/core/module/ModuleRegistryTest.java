package com.udcf.core.module;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Guards startup validation of the module set and lookup by id. */
class ModuleRegistryTest {

    /** Minimal module; only id and lab number matter to the registry. */
    private record TestModule(String id, int labNumber) implements ExperimentModule {
        @Override
        public String title() {
            return "Test " + id;
        }

        @Override
        public ModuleStatus status() {
            return ModuleStatus.IDLE;
        }

        @Override
        public void reset() {
        }
    }

    private static ModuleRegistry registryOf(ExperimentModule... modules) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        for (int i = 0; i < modules.length; i++) {
            factory.addBean("module" + i, modules[i]);
        }
        return new ModuleRegistry(factory.getBeanProvider(ExperimentModule.class));
    }

    @Test
    @DisplayName("an empty registry is valid")
    void emptyIsValid() {
        ModuleRegistry registry = registryOf();

        assertThat(registry.modules()).isEmpty();
        assertThat(registry.find("election")).isEmpty();
    }

    @Test
    @DisplayName("modules are sorted by lab number and the list is unmodifiable")
    void sortedByLabNumber() {
        ModuleRegistry registry = registryOf(
                new TestModule("election", 4), new TestModule("rmi", 1), new TestModule("matrix", 10));

        assertThat(registry.modules()).extracting(ExperimentModule::id)
                .containsExactly("rmi", "election", "matrix");
        assertThatThrownBy(() -> registry.modules().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("find and get return the module with the id")
    void findAndGet() {
        TestModule election = new TestModule("election", 4);
        ModuleRegistry registry = registryOf(election, new TestModule("rmi", 1));

        assertThat(registry.find("election")).containsSame(election);
        assertThat(registry.get("election")).isSameAs(election);
    }

    @Test
    @DisplayName("get of an unknown id throws UnknownModuleException")
    void getUnknown() {
        ModuleRegistry registry = registryOf(new TestModule("rmi", 1));

        assertThatThrownBy(() -> registry.get("nope"))
                .isInstanceOf(UnknownModuleException.class)
                .satisfies(e -> assertThat(((UnknownModuleException) e).moduleId()).isEqualTo("nope"));
    }

    @Test
    @DisplayName("duplicate ids are rejected")
    void duplicateIdRejected() {
        assertThatIllegalStateException()
                .isThrownBy(() -> registryOf(new TestModule("rmi", 1), new TestModule("rmi", 2)))
                .withMessageContaining("rmi");
    }

    @Test
    @DisplayName("duplicate lab numbers are rejected")
    void duplicateLabNumberRejected() {
        assertThatIllegalStateException()
                .isThrownBy(() -> registryOf(new TestModule("rmi", 1), new TestModule("other", 1)))
                .withMessageContaining("lab number 1");
    }

    @Test
    @DisplayName("blank and null ids are rejected")
    void blankIdRejected() {
        assertThatIllegalStateException().isThrownBy(() -> registryOf(new TestModule(" ", 1)));
        assertThatIllegalStateException().isThrownBy(() -> registryOf(new TestModule(null, 1)));
    }

    @Test
    @DisplayName("lab numbers 0 and 11 are rejected")
    void labNumberOutOfRangeRejected() {
        assertThatIllegalStateException().isThrownBy(() -> registryOf(new TestModule("a", 0)));
        assertThatIllegalStateException().isThrownBy(() -> registryOf(new TestModule("b", 11)));
    }
}
