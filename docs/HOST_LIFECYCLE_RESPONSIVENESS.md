# Responsive Host lifecycle dispatch

An off-site upload retry previously occupied the same single control worker as `server.stop` and `server.status`. Even though the backend uses a non-blocking operation lock, a stop request did not reach that lock until upload work finished. Repeated browser requests could then execute much later than the operator expected.

Only the Host opts into responsive dispatch. Paper keeps its existing serial worker. Host bulk I/O remains serial with a bounded queue of 32. Lifecycle commands have one dedicated worker with no queue: a second concurrent lifecycle request returns `BUSY` immediately. Short status and audit queries use two workers with a queue of eight. All dispatched actions still pass the existing authentication, device generation, scope, capability, confirmation, rate/replay and durable audit checks before invoking the backend.

The existing per-instance operation lock and node-wide backup lease remain authoritative. A lifecycle command conflicting with an upload, backup or restore returns `BUSY`; it is never saved for execution after that work ends. No upload is interrupted and no lifecycle request is automatically replayed. Status remains observable while a stop is actually saving worlds.

Host logs record lifecycle dispatch and completion using request IDs, action names and elapsed time only. Failures retain the existing sanitized failure record. No command parameters or credentials are logged. Shutdown interrupts and observes every worker under one shared drain deadline; a drain timeout never proves completion.

Regression coverage blocks an upload while requesting stop/status, proves that rejected stop never executes later, checks concurrent lifecycle rejection, confirms authorization/replay enforcement, and drains all workers while discarding queued reads.

Deployment requires a rebuilt Host JAR from the approved Core commit and the matching Dashboard fix for visible lifecycle errors. The Paper runtime behavior, wire protocol, action/scopes contracts, service units and VPS paths are unchanged. A Host replacement requires stopping and starting that instance's Host; warn the operator beforehand. Do not stop Minecraft for this fix, interrupt active backup work, or replace a live JAR. Preserve the old Host JAR for rollback and validate the registered fleet identity before starting the replacement.

Runtime acceptance must verify a single clean stop with no backup active, prompt `BUSY` with a conflicting upload, no later execution of the rejected intent, responsive status during shutdown, and continued server/identity isolation. These source tests do not certify the live VPS or complete off-site restore verification.
