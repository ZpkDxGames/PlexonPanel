#!/usr/bin/env python3
"""Install the pinned PlexonCore API without a network-dependent Maven install plugin."""
import argparse
import hashlib
from pathlib import Path
import shutil

EXPECTED_SHA256 = "61d625a717da9f46ee9231e1970d84b4c317ae12cf4090cdf7c9d39b6a1a9baf"
POM = """<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.zpkdxgames</groupId>
  <artifactId>PlexonCore</artifactId>
  <version>2.0.4</version>
  <packaging>jar</packaging>
</project>
"""


def provision(source: Path, repository: Path) -> None:
    if source.is_symlink() or not source.is_file():
        raise ValueError("PlexonCore API must be a regular file")
    if hashlib.sha256(source.read_bytes()).hexdigest() != EXPECTED_SHA256:
        raise ValueError("PlexonCore API checksum mismatch")
    target = repository / "com/zpkdxgames/PlexonCore/2.0.4"
    target.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target / "PlexonCore-2.0.4.jar")
    (target / "PlexonCore-2.0.4.pom").write_text(POM, encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--repository", type=Path, default=Path.home() / ".m2/repository")
    args = parser.parse_args()
    provision(args.jar, args.repository)
    print("Pinned PlexonCore 2.0.4 API provisioned")
