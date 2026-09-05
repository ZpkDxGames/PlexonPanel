# Troubleshooting

## Troubleshooting

| Symptom                    | Check                                                                                     |
| -------------------------- | ----------------------------------------------------------------------------------------- |
| Protocol mismatch          | Upgrade all components to v3 and re-pair browsers.                                        |
| Relay offline              | Outbound DNS/TLS, clock, WSS route and relay public key.                                  |
| Agent offline              | Paper and host are separate processes; host presence does not mean Paper runs.            |
| Expired/revoked credential | Generate a fresh role-bound code locally.                                                 |
| Capability unavailable     | Local policy, current scopes and correct source agent.                                    |
| Host rejected              | Paper-side host pin, shared UUID, exact unit and registry permissions.                    |
| File conflict              | Preserve draft, inspect actual file and save against a fresh hash.                        |
| Save lease refused         | Paper backup setting, host trust and automatic-job opt-in.                                |
| Recovery required          | Keep Paper stopped and follow local host recovery; never delete the journal to bypass it. |

Use sanitized diagnostics for support. Keep raw logs, player data and credentials out of public issues.
