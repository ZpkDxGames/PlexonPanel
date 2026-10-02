import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";

const filename = new URL("../host-agent/examples/fleet/00-plexonpanel-instance-control.rules", import.meta.url);
let rule;
runInNewContext(readFileSync(filename, "utf8"), { polkit: {
  Result: { YES: "yes", NO: "no" }, addRule(callback) { assert.equal(rule, undefined); rule = callback; },
} }, { timeout: 1000 });
function check(user, unit, verb, action = "org.freedesktop.systemd1.manage-units") {
  return rule({ id: action, lookup(key) { return key === "unit" ? unit : key === "verb" ? verb : undefined; } }, { user });
}
let checks = 0;
function expect(expected, ...args) { assert.equal(check(...args), expected); checks++; }
for (const key of ["plexoncraft", "server2", "a".repeat(28)]) {
  for (const verb of ["start", "stop", "restart"]) {
    expect("yes", `pph-${key}`, `minecraft@${key}.service`, verb);
    expect("no", `pph-${key}`, "minecraft@unrelated.service", verb);
  }
}
for (const target of ["ssh.service", "caddy.service", "plexoncraft.service", "plexonpanel-host@plexoncraft.service", "minecraft@plexoncraft", "minecraft@plexoncraft.service\n", "minecraft@*.service", undefined])
  expect("no", "pph-plexoncraft", target, "start");
for (const verb of ["reload", "reload-or-restart", "try-restart", "isolate", "kill", undefined])
  expect("no", "pph-plexoncraft", "minecraft@plexoncraft.service", verb);
for (const action of ["org.freedesktop.systemd1.manage-unit-files", "org.freedesktop.systemd1.set-environment", "org.freedesktop.systemd1.reload-daemon"])
  expect("no", "pph-plexoncraft", "minecraft@plexoncraft.service", "start", action);
for (const user of ["pph-../other", "pph-A", "pph-", `pph-${"a".repeat(29)}`, "pph-plexoncraft\n"])
  expect("no", user, "minecraft@plexoncraft.service", "start");
for (const user of ["root", "ubuntu", "mc-plexoncraft", "ordinary"])
  expect(undefined, user, "minecraft@plexoncraft.service", "start");
expect(undefined, "pph-plexoncraft", "minecraft@plexoncraft.service", "start", "org.freedesktop.login1.reboot");
console.log(`PASS: ${checks} executed authorization-rule cases. Production polkit remains NOT_EXECUTED.`);
