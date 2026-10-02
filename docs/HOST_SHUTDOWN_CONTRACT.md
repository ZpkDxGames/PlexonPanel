# Host shutdown contract

Host shutdown stops telemetry scheduling and rejects new controls before interrupting both operation workers. It observes actual termination under one shared forty-five-second deadline. Maintenance keeps its command channel open until its worker exits, so failure recovery can finish. Backup hashing, ZIP creation and ZIP verification honor cancellation. Existing durable maintenance state determines recovery after an interrupted operation; an interrupted countdown remains resumable and is not reported complete.

The fleet identity lease is released only after operation/telemetry executors, link workers, HTTP transport and journal readers terminate. If any worker exceeds the deadline, Host emits a fixed non-secret timeout code and retains the lease until process exit, when the kernel releases it. A hung worker cannot cause a second process to obtain the same identity early. Kernel node backup leases stay owned by the worker until its finally block or process death.

Paper closes its control, telemetry, transport, console and presence workers under a thirty-five-second deadline. A failed drain retains the process-held identity lease and rejects an in-process runtime reload. Restarting the whole Minecraft process is then required. Background uncaught errors log their exception class with a fixed code, avoiding arbitrary exception text or stack traces.

Final service templates must allow more than these application deadlines before systemd's final process termination. Successful shutdown and backup interruption on a real VPS remain separate runtime gates. Automated tests cover bounded timeout observation, preserved interruption, local verifier/hash cancellation, unstarted transport cleanup and active/queued control cancellation.
