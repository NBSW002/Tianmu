package com.tianmu.agent;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Migration ratchet: known debt is explicit; new coupling is rejected and removed debt must leave the list. */
class GovernanceArchitectureTest {
    private static final String BASE = "com.tianmu.agent.";
    // RequestOrchestrator is the existing application coordinator, not a generic execution component.
    private static final Set<String> APPLICATION_COORDINATORS = Set.of("runtime.RequestOrchestrator");

    @Test void executionKernelCannotGrowBusinessDependencies() {
        var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages(BASE);
        var actual = new TreeSet<String>();
        for (var type : classes) {
            if (!type.getName().startsWith(BASE + "runtime.")) continue;
            String origin = type.getName().substring(BASE.length()).split("\\$")[0];
            if (APPLICATION_COORDINATORS.contains(origin)) continue;
            for (var dependency : type.getDirectDependenciesFromSelf()) {
                String target = dependency.getTargetClass().getName();
                if (target.startsWith(BASE + "application.") || target.startsWith(BASE + "feedback.")
                        || target.startsWith(BASE + "video.") || target.startsWith(BASE + "interaction.")
                        || target.startsWith(BASE + "tool.builtin.") || target.startsWith(BASE + "adapters.")) {
                    actual.add(origin + " -> " + target.substring(BASE.length()).split("\\$")[0]);
                }
            }
        }
        var allowed = Set.of("runtime.ToolBatchLedger -> adapters.storage.FileObjectStore");
        assertEquals(new TreeSet<>(allowed), actual, "Known exceptions expire in batch 4; do not add a new business dependency to the kernel");
    }

    @Test void registeredBusinessToolNamesCannotSpreadThroughTheKernel() throws Exception {
        var names = new com.tianmu.agent.tool.ToolCatalog().all().keySet();
        var actual = new TreeSet<String>();
        var literal = Pattern.compile("\"([a-z][a-z0-9_]+)\"");
        var sourceRoot = Path.of("src/main/java/com/tianmu/agent");
        try (var files = Files.walk(sourceRoot.resolve("runtime"))) {
            for (var source : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String type = sourceRoot.relativize(source).toString().replace('\\', '.').replace('/', '.').replace(".java", "");
                if (APPLICATION_COORDINATORS.contains(type)) continue;
                var matcher = literal.matcher(Files.readString(source));
                while (matcher.find()) {
                    if (names.contains(matcher.group(1))) actual.add(type + " -> " + matcher.group(1));
                }
            }
        }
        Set<String> allowed = Set.of();
        assertEquals(new TreeSet<>(allowed), actual, "Remove exceptions when batch 4 moves business completion out of the kernel");
    }
}
