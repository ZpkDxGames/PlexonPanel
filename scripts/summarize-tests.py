#!/usr/bin/env python3
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
files = sorted(root.glob("**/build/test-results/test/TEST-*.xml"))
if not files:
    raise SystemExit("No JUnit XML reports found")

totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
for report in files:
    suite = ET.parse(report).getroot()
    for key in totals:
        totals[key] += int(suite.attrib.get(key, "0"))

out = root / "build" / "release" / "test-summary.txt"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text("\n".join(f"{key}={value}" for key, value in totals.items()) + "\n", encoding="utf-8")
print(out.read_text(encoding="utf-8"), end="")
