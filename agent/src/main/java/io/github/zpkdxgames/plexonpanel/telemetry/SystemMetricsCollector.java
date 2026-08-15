package io.github.zpkdxgames.plexonpanel.telemetry;

import io.github.zpkdxgames.plexonpanel.model.SystemSnapshot;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

public final class SystemMetricsCollector {
    private final Path serverRoot;
    private final Clock clock;
    private final java.lang.management.OperatingSystemMXBean genericOperatingSystem;
    private final com.sun.management.OperatingSystemMXBean extendedOperatingSystem;

    public SystemMetricsCollector(Path serverRoot) {
        this(serverRoot, Clock.systemUTC());
    }

    SystemMetricsCollector(Path serverRoot, Clock clock) {
        this.serverRoot = serverRoot.toAbsolutePath().normalize();
        this.clock = clock;
        this.genericOperatingSystem = ManagementFactory.getOperatingSystemMXBean();
        this.extendedOperatingSystem = genericOperatingSystem instanceof com.sun.management.OperatingSystemMXBean extended
            ? extended
            : null;
    }

    public SystemSnapshot collect() throws IOException {
        Runtime runtime = Runtime.getRuntime();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        FileStore fileStore = Files.getFileStore(serverRoot);

        double processCpu = -1.0;
        double systemCpu = -1.0;
        long physicalTotal = -1L;
        long physicalFree = -1L;
        if (extendedOperatingSystem != null) {
            processCpu = sanitizeLoad(extendedOperatingSystem.getProcessCpuLoad());
            systemCpu = sanitizeLoad(extendedOperatingSystem.getCpuLoad());
            physicalTotal = extendedOperatingSystem.getTotalMemorySize();
            physicalFree = extendedOperatingSystem.getFreeMemorySize();
        }

        return new SystemSnapshot(
            clock.instant().toString(),
            runtime.availableProcessors(),
            processCpu,
            systemCpu,
            genericOperatingSystem.getSystemLoadAverage(),
            heap.getUsed(),
            heap.getCommitted(),
            heap.getMax(),
            physicalTotal >= 0 && physicalFree >= 0 ? physicalTotal - physicalFree : -1L,
            physicalTotal,
            fileStore.getUsableSpace(),
            fileStore.getUnallocatedSpace(),
            fileStore.getTotalSpace(),
            ManagementFactory.getRuntimeMXBean().getUptime()
        );
    }

    private static double sanitizeLoad(double value) {
        if (!Double.isFinite(value) || value < 0) {
            return -1.0;
        }
        return Math.min(value, 1.0);
    }
}
