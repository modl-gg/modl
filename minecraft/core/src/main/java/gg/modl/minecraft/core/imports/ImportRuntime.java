package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.core.util.PluginLogger;
import lombok.Builder;
import lombok.Value;

import java.util.concurrent.ScheduledExecutorService;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@Value
@Builder
class ImportRuntime {
    ImportSources sources;
    ImportBackend backend;
    ImportCalls calls;
    ImportFiles files;
    ImportReporter reporter;
    ImportJobFollower follower;
    ScheduledExecutorService monitor;
    ImportSignal lifecycle;
    ImportTimings timings;
    PluginLogger logger;
    Supplier<String> serverName;
    Supplier<String> warningText;
    LongSupplier clock;
}
