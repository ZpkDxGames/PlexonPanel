# Fleet telemetry authority

Signed system telemetry includes immutable nodeId, `metricScope: NODE` and processRole when
fleet metadata is enabled. Node CPU, memory, load, network and filesystem totals describe the
shared machine. Deduplicate fleet node totals by authenticated nodeId, require a fresh connected
Host sample and retain its capturedAt timestamp. Paper/Host process fields must remain attributed
to their sending JVM: a Host process is not the Minecraft server.

The configured Minecraft unit's `service.status.resources` contains systemd cgroup accounting:
`scope: MINECRAFT_SERVICE`, `source: SYSTEMD_CGROUP`, `cpuUnit: PERCENT_OF_ONE_CORE`, optional
cpuPercent/memoryBytes, availability flags and capturedAt. CPU uses differences in CPUUsageNSec
and monotonic wall time. 150% represents 1.5 CPU cores, not 150% of the entire node. MemoryCurrent
includes the unit's cgroup, including its child processes; it is not a Java heap estimate.

The first CPU sample, PID changes, counter resets, inactive service, missing accounting and
invalid counters report unavailable values. No zero is invented for missing measurements.
Enable systemd resource accounting in the final instance templates. Access stays limited to
the configured exact unit and never enumerates environment or unrelated units.

Executed tests cover CPU units/intervals, PID restart, counter reset, missing/invalid accounting,
offline state and zero elapsed time. Production systemd accounting and fleet display accuracy
remain separate runtime certification gates.
