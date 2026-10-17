"""Exercise SQLite platform packaging and reject incomplete/foreign native payloads."""
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

root = Path(__file__).resolve().parent.parent
jar = root / "host-agent/build/libs/plexonpanel-host-5.0.0.jar"
verifier = root / "scripts/verify-host-portability.py"

def check(path):
    return subprocess.run([sys.executable, str(verifier), str(path)], capture_output=True, text=True)

assert check(jar).returncode == 0, check(jar).stderr
with tempfile.TemporaryDirectory() as directory:
    for mode in ("foreign", "missing"):
        target = Path(directory) / f"{mode}.jar"
        with zipfile.ZipFile(jar) as source, zipfile.ZipFile(target, "w") as dest:
            for entry in source.infolist():
                if mode == "missing" and entry.filename == "org/sqlite/native/Linux/aarch64/libsqlitejdbc.so":
                    continue
                dest.writestr(entry, source.read(entry.filename))
            if mode == "foreign":
                dest.writestr("native/unapproved.so", b"synthetic rejection fixture")
        response = check(target)
        assert response.returncode != 0, mode
        assert ("unexpected native payload" if mode == "foreign" else "missing required SQLite") in response.stderr
print("PASS: complete Linux SQLite platform set accepted; foreign and missing native payloads rejected")
