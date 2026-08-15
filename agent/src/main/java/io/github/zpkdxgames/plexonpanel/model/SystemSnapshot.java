package io.github.zpkdxgames.plexonpanel.model;

public record SystemSnapshot(
    String capturedAt,
    int availableProcessors,
    double processCpuLoad,
    double systemCpuLoad,
    double systemLoadAverage,
    long jvmHeapUsedBytes,
    long jvmHeapCommittedBytes,
    long jvmHeapMaximumBytes,
    long physicalMemoryUsedBytes,
    long physicalMemoryTotalBytes,
    long diskUsableBytes,
    long diskUnallocatedBytes,
    long diskTotalBytes,
    long processUptimeMillis
) {
}
