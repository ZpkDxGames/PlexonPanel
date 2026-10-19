# PlexonPanel Host on Linux ARM64

PlexonPanel Host supports 64-bit Linux on both `x86_64` and ARM64 (`aarch64`). The Host release is an architecture-neutral Java 25 JAR; it does not bundle JNI/shared-library payloads. The architecture-specific requirement is the installed Java 25 runtime.

## Supported Host platforms

- `linux-x64` — Ubuntu 24.04 / `x86_64`
- `linux-arm64` — Ubuntu 24.04 / `aarch64`

Both platforms are mandatory CI/release gates. A stable release must build and execute the Host test suite on native GitHub Linux runners for both architectures before the release job is allowed to publish.

## ARM64 VPS preflight

On the server:

```sh
uname -m
/usr/bin/java -version
/usr/bin/java -XshowSettings:properties -version 2>&1 | grep -E 'os.arch|java.version'
```

Expected architecture on an ARM64 VPS:

```text
aarch64
```

The Java runtime must be a native ARM64 Java 25 installation. Do not install an x86 JVM under emulation for production Host service use.

## Verify a Host JAR before deployment

The repository and release examples include `scripts/verify-host-portability.py`. It checks that the Host JAR:

- contains the Host main class and Host-local RCON implementation;
- targets Java 25 bytecode;
- has the expected executable JAR manifest;
- contains no `.so`, `.dll`, `.dylib`, `.jnilib`, or native payload directory that would make the JAR architecture-specific;
- is being verified on the requested Linux architecture.

For an ARM64 VPS or ARM64 CI runner:

```sh
python3 scripts/verify-host-portability.py \
  plexonpanel-host-<version>.jar \
  --expected-platform linux-arm64
```

For x64:

```sh
python3 scripts/verify-host-portability.py \
  plexonpanel-host-<version>.jar \
  --expected-platform linux-x64
```

## Deployment rule

The filename `plexonpanel-host-<version>.jar` is intentionally the same on x64 and ARM64 because the JAR is portable Java bytecode. Do not choose a Host JAR based only on the machine where it was downloaded. Instead:

1. use a Host JAR produced from the same source commit as the Paper/plugin build you intend to run;
2. verify `SHA256SUMS.txt` or the CI artifact digest;
3. run the portability verifier on the target architecture;
4. replace the Host JAR while `plexonpanel-host.service` is stopped;
5. start the Host service and verify it reaches `active` without a configuration-schema error.

A stale Host JAR can have the same semantic filename/version while lacking newer configuration keys if it was built before later source changes. For source builds or CI candidates, source commit identity and checksum are therefore authoritative in addition to the version string.

## systemd example

The service should invoke the native Java runtime directly; no architecture-specific wrapper is required:

```ini
ExecStart=/usr/bin/java -Xms32m -Xmx256m -jar /opt/plexonpanel-host/plexonpanel-host-<version>.jar /etc/plexonpanel-host/host-config.json
```

The Host must continue to run under its dedicated non-root service identity. ARM64 support does not change the existing filesystem, systemd, RCON, authorization, or secret-storage security requirements.

## Release metadata

`release-manifest.json` declares:

```json
{
  "hostArtifactType": "architecture-neutral-java-jar",
  "hostSupportedPlatforms": ["linux-x64", "linux-arm64"]
}
```

The normal successful stable-release path is blocked unless native x64 and ARM64 Host verification jobs both pass.
