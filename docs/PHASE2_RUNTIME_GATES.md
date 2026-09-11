# PlexonPanel 3.2.0 runtime-certification gates

Stable promotion is blocked until an actual end-to-end PlexonCraft test is recorded for the exact Paper, Host, relay and dashboard candidates.

Required gates:

- upgrade from the current production Panel without deleting the stable server identity/device registry;
- Paper plugin starts and registers with PlexonCore correctly;
- host companion starts under the intended service identity;
- pairing is preserved/migrated and new one-use pairing succeeds;
- dashboard connects to the correct server through the candidate relay;
- online/offline state, players, TPS, MSPT, heap, process/system CPU and uptime are sane; CPU unavailable is never displayed as 0%;
- console stream is bounded, live and reconnect-safe; chat stream has correct origin and no echo loop;
- permitted actions succeed and denied/disabled capabilities fail server-side/host-side;
- remote command execution is main-thread controlled and returns one structured result per request ID;
- duplicate/stale request replay, reconnect replay, malformed payload, protocol mismatch and wrong-server routing are rejected;
- plugin restart, host restart, dashboard refresh and relay interruption all recover deterministically without duplicate server records;
- stale sessions cannot issue actions after replacement/reconnect;
- multi-server isolation is verified if more than one server is configured;
- host-level capability separation and service/process identity checks pass;
- Cloudflare Tunnel/origin configuration exposes only the intended relay surface;
- Spark/MSPT comparison shows no material regression from the 3.1.1 baseline;
- at least 30 minutes of soak completes without unbounded queue/DOM/memory growth or busy reconnect loops;
- zero HIGH/CRITICAL security defects remain.

Record exact component SHAs, deployed relay configuration revision, test operator, start/end timestamps, failures and rollback result with the acceptance evidence. Only then may the Phase 2 PRs be merged and stable 3.2.0 be considered.
