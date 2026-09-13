from pathlib import Path
import re


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    if text.count(old) != 1:
        raise SystemExit(f'{path}: expected exactly one match, got {text.count(old)}')
    p.write_text(text.replace(old, new))

# Keep the new BackupConfig field source-compatible with existing in-repo fixtures and operators.
replace_once(
    'host-agent/src/main/java/io/github/zpkdxgames/plexonpanel/host/HostConfig.java',
    '''      String rcloneRemote,\n      String rcloneConfig,\n      List<String> liveSnapshotExcludes) {}''',
    '''      String rcloneRemote,\n      String rcloneConfig,\n      List<String> liveSnapshotExcludes) {\n    public BackupConfig(\n        boolean enabled,\n        String directory,\n        List<String> include,\n        int retentionCount,\n        int intervalMinutes,\n        long maximumBytes,\n        boolean restoreEnabled,\n        String rcloneExecutable,\n        String rcloneRemote,\n        String rcloneConfig) {\n      this(\n          enabled,\n          directory,\n          include,\n          retentionCount,\n          intervalMinutes,\n          maximumBytes,\n          restoreEnabled,\n          rcloneExecutable,\n          rcloneRemote,\n          rcloneConfig,\n          HostConfig.defaultLiveSnapshotExcludes());\n    }\n  }'''
)

# Preflight must report the real unreadable count, while examples remain bounded.
p = Path('host-agent/src/main/java/io/github/zpkdxgames/plexonpanel/host/BackupPreflight.java')
text = p.read_text()
text = text.replace('long[] durable = {0}, volatileExcluded = {0};', 'long[] durable = {0}, volatileExcluded = {0}, unreadableCount = {0};')
text = text.replace('''                } catch (IOException failure) {\n                  addBounded(unreadable, relative);\n                }''', '''                } catch (IOException failure) {\n                  unreadableCount[0]++;\n                  addBounded(unreadable, relative);\n                }''')
text = text.replace('''                else addBounded(unreadable, relative);''', '''                else {\n                  unreadableCount[0]++;\n                  addBounded(unreadable, relative);\n                }''')
text = text.replace('''      } catch (IOException failure) {\n        addBounded(unreadable, include);\n      }''', '''      } catch (IOException failure) {\n        unreadableCount[0]++;\n        addBounded(unreadable, include);\n      }''')
text = text.replace('result.put("unreadableDurableCount", unreadable.size());', 'result.put("unreadableDurableCount", unreadableCount[0]);')
p.write_text(text)

# Running-provider truth and explicit test state.
p = Path('host-agent/src/main/java/io/github/zpkdxgames/plexonpanel/host/RcloneBackupProvider.java')
text = p.read_text()
text = text.replace('import java.io.*;', 'import io.github.zpkdxgames.plexonpanel.control.OperationFailure;\nimport java.io.*;')
text = text.replace(
    '  private final HostConfig.BackupConfig config;\n',
    '  private final HostConfig.BackupConfig config;\n  private volatile String lastTestAt = "";\n  private volatile String lastTestState = "NOT_TESTED";\n'
)
pattern = re.compile(r'  public Map<String, Object> status\(\) \{.*?\n  public Promotion uploadAndPromote', re.S)
match = pattern.search(text)
if not match:
    raise SystemExit('RcloneBackupProvider status/test block not found')
replacement = '''  public Map<String, Object> status() {\n    boolean configured = configured();\n    String state =\n        !configured\n            ? "LOCAL"\n            : lastTestState.equals("CONNECTED")\n                ? "CONNECTED"\n                : lastTestState.equals("ERROR") ? "DEGRADED" : "CONFIGURED_UNTESTED";\n    Map<String, Object> result = new LinkedHashMap<>();\n    result.put("provider", configured ? "RCLONE" : "LOCAL");\n    result.put("providerMode", configured ? "RCLONE" : "LOCAL");\n    result.put("configured", configured);\n    result.put("status", state);\n    result.put("remote", configured ? safeRemoteLabel() : "");\n    result.put("lastTestAt", lastTestAt);\n    result.put("lastTestState", lastTestState);\n    return Map.copyOf(result);\n  }\n\n  public Map<String, Object> test(int timeoutSeconds) {\n    if (!configured())\n      throw new OperationFailure(\n          "RCLONE_UNAVAILABLE",\n          "PROVIDER_TEST",\n          "No off-site rclone provider is configured on the running Host.",\n          false);\n    long started = System.nanoTime();\n    String checkedAt = Instant.now().toString();\n    try {\n      requireConfigured();\n      run(\n          List.of(\n              config.rcloneExecutable(),\n              "lsjson",\n              remoteRoot(),\n              "--max-depth",\n              "1",\n              "--config",\n              config.rcloneConfig()),\n          timeoutSeconds);\n      lastTestAt = checkedAt;\n      lastTestState = "CONNECTED";\n      return Map.of(\n          "provider",\n          "RCLONE",\n          "status",\n          "CONNECTED",\n          "remote",\n          safeRemoteLabel(),\n          "checkedAt",\n          checkedAt,\n          "durationMillis",\n          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));\n    } catch (OperationFailure failure) {\n      lastTestAt = checkedAt;\n      lastTestState = "ERROR";\n      throw failure;\n    } catch (Exception failure) {\n      lastTestAt = checkedAt;\n      lastTestState = "ERROR";\n      throw new OperationFailure(\n          "RCLONE_TEST_FAILED",\n          "PROVIDER_TEST",\n          "The running Host could not reach or validate the configured off-site provider.",\n          true);\n    }\n  }\n\n  public Promotion uploadAndPromote'''
text = text[:match.start()] + replacement + text[match.end():]
p.write_text(text)

# Wire preflight and runtime configuration provenance into HostMain.
p = Path('host-agent/src/main/java/io/github/zpkdxgames/plexonpanel/host/HostMain.java')
text = p.read_text()
text = text.replace(
    '    HostConfig config = HostConfig.load(Path.of(args[0]));\n    Path data = Path.of(config.dataDirectory());',
    '    Path configPath = Path.of(args[0]).toAbsolutePath().normalize();\n    Instant hostStartedAt = Instant.now();\n    long configLoadedMtime = Files.getLastModifiedTime(configPath, LinkOption.NOFOLLOW_LINKS).toMillis();\n    HostConfig config = HostConfig.load(configPath);\n    Path data = Path.of(config.dataDirectory());'
)
text = text.replace('new PaperSaveLease(connection, devices)', 'new PaperSaveLease(connection)')
old_provider = '''                      "provider",\n                      config.backups().rcloneRemote() == null\n                              || config.backups().rcloneRemote().isBlank()\n                          ? "LOCAL"\n                          : "RCLONE",'''
new_provider = '''                      "provider",\n                      fullBackups.providerStatus().getOrDefault("provider", "UNKNOWN"),'''
if old_provider not in text:
    raise SystemExit('HostMain backup.list provider fallback block not found')
text = text.replace(old_provider, new_provider)
needle = '''                case "backup.create" -> {\n                  var result = backups.create(device, false, false);'''
insert = '''                case "backup.preflight" -> {\n                  var result = new LinkedHashMap<>(backups.preflight(connection::authenticated));\n                  result.putAll(fullBackups.providerStatus());\n                  result.put("hostStartedAt", hostStartedAt.toString());\n                  result.put("hostConfigLoadedAt", Instant.ofEpochMilli(configLoadedMtime).toString());\n                  result.put("hostConfigRestartRequired", configChanged(configPath, configLoadedMtime));\n                  return Map.copyOf(result);\n                }\n                case "backup.create" -> {\n                  var result = backups.create(device, false, false);'''
if needle not in text:
    raise SystemExit('HostMain backup.create block not found')
text = text.replace(needle, insert)
text = text.replace(
    '''                case "provider.status" -> {\n                  return fullBackups.providerStatus();\n                }''',
    '''                case "provider.status" -> {\n                  var result = new LinkedHashMap<>(fullBackups.providerStatus());\n                  result.put("hostStartedAt", hostStartedAt.toString());\n                  result.put("hostConfigLoadedAt", Instant.ofEpochMilli(configLoadedMtime).toString());\n                  result.put("hostConfigRestartRequired", configChanged(configPath, configLoadedMtime));\n                  return Map.copyOf(result);\n                }'''
)
old_scheduled = '''                                "actionType", "backup.create",\n                                "outcome", "FAILED",\n                                "code", "BACKUP_FAILED"));'''
new_scheduled = '''                                "actionType", "backup.create",\n                                "outcome", "FAILED",\n                                "code", e instanceof OperationFailure failure ? failure.code() : "BACKUP_FAILED",\n                                "metadata",\n                                    e instanceof OperationFailure failure\n                                        ? failure.safeData()\n                                        : Map.of()));'''
if old_scheduled not in text:
    raise SystemExit('HostMain scheduled failure audit block not found')
text = text.replace(old_scheduled, new_scheduled)
helper_anchor = '''  private static void requireOwner(DeviceRegistry.Device device) {\n    if (device == null || !"Owner".equals(device.role())) throw new SecurityException("OWNER_REQUIRED");\n  }'''
helper = '''  private static boolean configChanged(Path path, long loadedMtime) {\n    try {\n      return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis() != loadedMtime;\n    } catch (Exception ignored) {\n      return false;\n    }\n  }\n\n''' + helper_anchor
if helper_anchor not in text:
    raise SystemExit('HostMain helper anchor not found')
text = text.replace(helper_anchor, helper)
p.write_text(text)

# Release candidate version.
p = Path('build.gradle.kts')
text = p.read_text()
if 'version = "3.4.0"' not in text:
    raise SystemExit('Expected 3.4.0 version not found')
p.write_text(text.replace('version = "3.4.0"', 'version = "3.4.1"', 1))

print('PlexonPanel 3.4.1 control-plane wiring applied')
