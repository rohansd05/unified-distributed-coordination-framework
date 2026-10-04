package com.udcf.core.module;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Every {@link ExperimentModule} bean, validated once at startup and listed for the
 * sidebar.
 *
 * <p>Works with zero modules. Startup fails on a blank or duplicate id, a duplicate lab
 * number, or a lab number outside 1..10.</p>
 */
@Component
public class ModuleRegistry {

    private final List<ExperimentModule> modules;

    public ModuleRegistry(ObjectProvider<ExperimentModule> provider) {
        List<ExperimentModule> found = provider.orderedStream().toList();
        validate(found);
        this.modules = found.stream()
                .sorted(Comparator.comparingInt(ExperimentModule::labNumber))
                .toList();
    }

    private static void validate(List<ExperimentModule> found) {
        Set<String> ids = new HashSet<>();
        Set<Integer> labNumbers = new HashSet<>();
        for (ExperimentModule module : found) {
            String id = module.id();
            if (id == null || id.isBlank()) {
                throw new IllegalStateException("Module " + module.getClass().getName() + " has a blank id");
            }
            if (!ids.add(id)) {
                throw new IllegalStateException("Duplicate module id '" + id + "'");
            }
            int lab = module.labNumber();
            if (lab < 1 || lab > 10) {
                throw new IllegalStateException("Module '" + id + "' has lab number " + lab + "; must be 1..10");
            }
            if (!labNumbers.add(lab)) {
                throw new IllegalStateException("Duplicate lab number " + lab + " (module '" + id + "')");
            }
        }
    }

    /** Every module, sorted by lab number. Unmodifiable. */
    public List<ExperimentModule> modules() {
        return modules;
    }

    public Optional<ExperimentModule> find(String id) {
        return modules.stream().filter(module -> Objects.equals(module.id(), id)).findFirst();
    }

    /** @throws UnknownModuleException if no module has {@code id} */
    public ExperimentModule get(String id) {
        return find(id).orElseThrow(() -> new UnknownModuleException(id));
    }
}
