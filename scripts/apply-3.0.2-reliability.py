from pathlib import Path


def replace(text: str, old: str, new: str, label: str, count: int = 1) -> str:
    if old not in text:
        raise SystemExit(f"missing patch anchor: {label}")
    return text.replace(old, new, count)


# Host: access reconciliation is mutation-driven, never reconnect-driven.
p = Path("host-agent/src/main/java/io/github/zpkdxgames/plexonpanel/host/HostMain.java")
host = p.read_text()
if "AtomicLong sentRevision = new AtomicLong(-1);" in host:
    host = host.replace("    AtomicLong sentRevision = new AtomicLong(-1);\n", "", 1)
    host = replace(
        host,
        '''            connection.send("service.status", status, MessagePriority.TELEMETRY);\n            var state = devices.snapshot();\n            if (sentRevision.getAndSet(state.revision()) != state.revision()) engine.syncAccess();''',
        '''            connection.send("service.status", status, MessagePriority.TELEMETRY);''',
        "Host periodic access sync",
    )
    host = replace(
        host,
        '''        () -> {\n          sentRevision.set(-1);\n          telemetryScheduler.execute(fastSnapshot);\n          scheduler.execute(serviceSnapshot);\n        });''',
        '''        () -> {\n          telemetryScheduler.execute(fastSnapshot);\n          scheduler.execute(serviceSnapshot);\n        });''',
        "Host reconnect access reset",
    )
p.write_text(host)

# Paper: separate authenticated-session initialization from dashboard snapshot refresh,
# scope disconnect callbacks to the socket that actually failed, and expose sanitized diagnostics.
p = Path("agent/src/main/java/io/github/zpkdxgames/plexonpanel/transport/GatewayClient.java")
gateway = p.read_text()
if "snapshotRequestHandler" not in gateway:
    gateway = replace(
        gateway,
        '''  private volatile Consumer<DecodedMessage> inboundHandler = ignored -> {};\n  private volatile Runnable connectedHandler = () -> {};\n  private volatile String lastError = "";''',
        '''  private volatile Consumer<DecodedMessage> inboundHandler = ignored -> {};\n  private volatile Runnable connectedHandler = () -> {};\n  private volatile Runnable snapshotRequestHandler = () -> {};\n  private volatile String lastError = "";\n  private volatile String lastProtocolRejectionCode = "";\n  private volatile String lastAcceptedRelayMessageType = "";''',
        "Gateway handlers and diagnostics fields",
    )
    gateway = replace(
        gateway,
        '''  public void setConnectedHandler(Runnable handler) {\n    this.connectedHandler = Objects.requireNonNull(handler, "handler");\n  }''',
        '''  public void setConnectedHandler(Runnable handler) {\n    this.connectedHandler = Objects.requireNonNull(handler, "handler");\n  }\n\n  public void setSnapshotRequestHandler(Runnable handler) {\n    this.snapshotRequestHandler = Objects.requireNonNull(handler, "handler");\n  }''',
        "snapshot handler setter",
    )
    gateway = replace(
        gateway,
        '''              if (error != null) {\n                handleDisconnect("Connection failed: " + rootMessage(error));\n              }''',
        '''              if (error != null) {\n                handleDisconnect(null, "Connection failed: " + rootMessage(error));\n              }''',
        "connect failure scoping",
    )
    gateway = replace(
        gateway,
        '''  private void senderLoop() {\n    while (running.get() && !Thread.currentThread().isInterrupted()) {\n      try {\n        OutboundMessage message = outbound.take();\n        java.util.concurrent.CompletableFuture<WebSocket> sent;\n        synchronized (this) {\n          WebSocket socket = webSocket;\n          if (socket == null\n              || state.get() != ConnectionState.CONNECTED\n              || !message.session().equals(wireSession.nonce())) {\n            droppedMessages.incrementAndGet();\n            continue;\n          }\n          sent = socket.sendText(message.encoded(), true);\n        }\n        sent.get(10, TimeUnit.SECONDS);\n      } catch (InterruptedException interrupted) {\n        Thread.currentThread().interrupt();\n        return;\n      } catch (Exception error) {\n        handleDisconnect("Send failed: " + rootMessage(error));\n      }\n    }\n  }''',
        '''  private void senderLoop() {\n    while (running.get() && !Thread.currentThread().isInterrupted()) {\n      WebSocket attemptedSocket = null;\n      try {\n        OutboundMessage message = outbound.take();\n        java.util.concurrent.CompletableFuture<WebSocket> sent;\n        synchronized (this) {\n          attemptedSocket = webSocket;\n          if (attemptedSocket == null\n              || state.get() != ConnectionState.CONNECTED\n              || !message.session().equals(wireSession.nonce())) {\n            droppedMessages.incrementAndGet();\n            continue;\n          }\n          sent = attemptedSocket.sendText(message.encoded(), true);\n        }\n        sent.get(10, TimeUnit.SECONDS);\n      } catch (InterruptedException interrupted) {\n        Thread.currentThread().interrupt();\n        return;\n      } catch (Exception error) {\n        handleDisconnect(attemptedSocket, "Send failed: " + rootMessage(error));\n      }\n    }\n  }''',
        "sender socket scoping",
    )
    gateway = replace(
        gateway,
        '''      lastMessageAt = Instant.now();\n      if (handleControlMessage(message)) {\n        return;\n      }''',
        '''      lastMessageAt = Instant.now();\n      if (handleControlMessage(message)) {\n        lastAcceptedRelayMessageType = message.envelope().type();\n        return;\n      }''',
        "control accepted message diagnostic",
    )
    gateway = replace(
        gateway,
        '''      if (!authenticated.get()) throw new SecurityException("Session is not authenticated");\n      inboundHandler.accept(message);''',
        '''      if (!authenticated.get()) throw new SecurityException("Session is not authenticated");\n      inboundHandler.accept(message);\n      lastAcceptedRelayMessageType = message.envelope().type();''',
        "inbound accepted message diagnostic",
    )
    gateway = replace(
        gateway,
        '''      case "gateway.snapshot_request" -> {\n        runConnectedHandler("Snapshot refresh handler failed");\n        yield true;\n      }''',
        '''      case "gateway.snapshot_request" -> {\n        runSnapshotRequestHandler();\n        yield true;\n      }''',
        "snapshot refresh separation",
    )
    gateway = replace(
        gateway,
        '''                            scheduler.execute(\n                                () -> handleDisconnect("Heartbeat failed: " + rootMessage(error)));''',
        '''                            scheduler.execute(\n                                () ->\n                                    handleDisconnect(\n                                        socket, "Heartbeat failed: " + rootMessage(error)));''',
        "heartbeat socket scoping",
    )
    gateway = replace(
        gateway,
        '''              if (running.get() && webSocket == expectedSocket && !authenticated.get()) {\n                handleDisconnect("Relay authentication timed out");\n              }''',
        '''              if (running.get() && webSocket == expectedSocket && !authenticated.get()) {\n                handleDisconnect(expectedSocket, "Relay authentication timed out");\n              }''',
        "authentication timeout scoping",
    )
    gateway = replace(
        gateway,
        '''  private void runConnectedHandler(String failureMessage) {\n    try {\n      connectedHandler.run();\n    } catch (RuntimeException error) {\n      plugin.getLogger().log(Level.WARNING, failureMessage, error);\n    }\n  }\n\n  private synchronized void handleDisconnect(String reason) {\n    if (!running.get()) {\n      return;\n    }''',
        '''  private void runConnectedHandler(String failureMessage) {\n    try {\n      connectedHandler.run();\n    } catch (RuntimeException error) {\n      plugin.getLogger().log(Level.WARNING, failureMessage, error);\n    }\n  }\n\n  private void runSnapshotRequestHandler() {\n    try {\n      snapshotRequestHandler.run();\n    } catch (RuntimeException error) {\n      plugin.getLogger().log(Level.WARNING, "Snapshot refresh handler failed", error);\n    }\n  }\n\n  private synchronized void handleDisconnect(WebSocket expectedSocket, String reason) {\n    if (!running.get()) {\n      return;\n    }\n    if (expectedSocket != null && webSocket != expectedSocket) {\n      return;\n    }''',
        "scoped disconnect implementation",
    )
    gateway = replace(
        gateway,
        '''  public String lastError() {\n    return lastError;\n  }\n\n  public Instant lastConnectedAt() {''',
        '''  public String lastError() {\n    return lastError;\n  }\n\n  public int reconnectAttempts() {\n    return reconnectAttempts;\n  }\n\n  public String lastProtocolRejectionCode() {\n    return lastProtocolRejectionCode;\n  }\n\n  public String lastAcceptedRelayMessageType() {\n    return lastAcceptedRelayMessageType;\n  }\n\n  public String currentSessionNoncePrefix() {\n    String nonce = wireSession.nonce();\n    return nonce.substring(0, Math.min(8, nonce.length()));\n  }\n\n  public long pendingCriticalMessages() {\n    return outbound.stream().filter(m -> m.priority() == MessagePriority.CRITICAL).count();\n  }\n\n  public Instant lastConnectedAt() {''',
        "diagnostics getters",
    )
    gateway = replace(
        gateway,
        '''    public CompletionStage<?> onPong(WebSocket socket, ByteBuffer message) {\n      lastMessageAt = Instant.now();\n      socket.request(1);\n      return null;\n    }''',
        '''    public CompletionStage<?> onPong(WebSocket socket, ByteBuffer message) {\n      if (socket != webSocket) return null;\n      lastMessageAt = Instant.now();\n      socket.request(1);\n      return null;\n    }''',
        "stale pong scoping",
    )
    gateway = replace(
        gateway,
        '''    public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {\n      if (running.get()) {\n        scheduler.execute(\n            () -> handleDisconnect("Relay closed connection (" + statusCode + "): " + reason));\n      }\n      return null;\n    }\n\n    @Override\n    public void onError(WebSocket socket, Throwable error) {\n      if (running.get()) {\n        scheduler.execute(() -> handleDisconnect("WebSocket error: " + rootMessage(error)));\n      }\n    }''',
        '''    public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {\n      if (statusCode == 4008) rememberProtocolRejection(reason);\n      if (running.get()) {\n        scheduler.execute(\n            () ->\n                handleDisconnect(\n                    socket,\n                    "Relay closed connection (" + statusCode + "): " + reason));\n      }\n      return null;\n    }\n\n    @Override\n    public void onError(WebSocket socket, Throwable error) {\n      if (running.get()) {\n        scheduler.execute(\n            () -> handleDisconnect(socket, "WebSocket error: " + rootMessage(error)));\n      }\n    }''',
        "listener stale callback scoping",
    )
    anchor = '''  private static String requiredString(JsonObject object, String name) {'''
    helper = '''  private void rememberProtocolRejection(String reason) {\n    String value = reason == null ? "" : reason.trim();\n    int separator = value.lastIndexOf(':');\n    String candidate = separator >= 0 ? value.substring(separator + 1).trim() : value;\n    lastProtocolRejectionCode =\n        candidate.matches("[A-Z][A-Z0-9_]{1,63}") ? candidate : "PROTOCOL_REJECTED";\n  }\n\n'''
    if anchor not in gateway:
        raise SystemExit("missing rejection diagnostic helper anchor")
    gateway = gateway.replace(anchor, helper + anchor, 1)
p.write_text(gateway)

# Authenticated startup keeps authoritative access sync. Browser refresh only asks for snapshots.
p = Path("agent/src/main/java/io/github/zpkdxgames/plexonpanel/AgentRuntime.java")
runtime = p.read_text()
if "setSnapshotRequestHandler" not in runtime:
    runtime = replace(
        runtime,
        '''          telemetry.sendInitialSnapshots();\n          console.sendRecentSnapshot();\n        });\n  }''',
        '''          telemetry.sendInitialSnapshots();\n          console.sendRecentSnapshot();\n        });\n    gateway.setSnapshotRequestHandler(\n        () -> {\n          telemetry.sendInitialSnapshots();\n          console.sendRecentSnapshot();\n        });\n  }''',
        "Paper snapshot request handler",
    )
p.write_text(runtime)

# Paper diagnostics requested by the 3.0.2 brief.
p = Path("agent/src/main/java/io/github/zpkdxgames/plexonpanel/command/PlexonPanelCommand.java")
command = p.read_text()
if '"Reconnect attempts"' not in command:
    command = replace(
        command,
        '''    sendRow(sender, "Last connected", formatInstant(gateway.lastConnectedAt()));\n    sendRow(sender, "Last relay message", formatInstant(gateway.lastMessageAt()));''',
        '''    sendRow(sender, "Last connected", formatInstant(gateway.lastConnectedAt()));\n    sendRow(sender, "Last relay message", formatInstant(gateway.lastMessageAt()));\n    sendRow(sender, "Reconnect attempts", Integer.toString(gateway.reconnectAttempts()));\n    sendRow(sender, "Session nonce prefix", gateway.currentSessionNoncePrefix());\n    sendRow(\n        sender,\n        "Pending critical messages",\n        Long.toString(gateway.pendingCriticalMessages()));\n    if (!gateway.lastAcceptedRelayMessageType().isBlank())\n      sendRow(sender, "Last accepted relay message", gateway.lastAcceptedRelayMessageType());\n    if (!gateway.lastProtocolRejectionCode().isBlank())\n      sendRow(sender, "Last protocol rejection", gateway.lastProtocolRejectionCode());''',
        "diagnostics rows",
    )
p.write_text(command)

# Version and full-control deployment surfaces.
replacements = {
    "build.gradle.kts": [('version = "3.0.1"', 'version = "3.0.2"')],
    "agent/src/main/resources/plugin.yml": [('version: "3.0.1"', 'version: "3.0.2"')],
    ".github/workflows/build.yml": [("PlexonPanel-3.0.1-${{ matrix.os }}", "PlexonPanel-3.0.2-${{ matrix.os }}")],
    "docs/release-gates.json": [('"version": "3.0.1"', '"version": "3.0.2"')],
    "protocol/src/main/java/io/github/zpkdxgames/plexonpanel/control/ControlEngine.java": [('"version",\n          "3.0.0"', '"version",\n          "3.0.2"')],
}
for name, pairs in replacements.items():
    path = Path(name)
    text = path.read_text()
    for old, new in pairs:
        if old in text:
            text = text.replace(old, new, 1)
    path.write_text(text)

p = Path("agent/examples/config-full-control.yml")
config = p.read_text().replace("# PlexonPanel 3.0.1", "# PlexonPanel 3.0.2", 1)
config = config.replace("  default-pair-role: Observer", "  default-pair-role: Owner", 1)
p.write_text(config)

p = Path("host-agent/examples/plexonpanel-host.service")
unit = p.read_text().replace("plexonpanel-host-3.0.1.jar", "plexonpanel-host-3.0.2.jar", 1)
if "SuccessExitStatus=143" not in unit:
    unit = unit.replace("Restart=on-failure", "SuccessExitStatus=143\nRestart=on-failure", 1)
p.write_text(unit)

p = Path("README.md")
readme = p.read_text().replace("# PlexonPanel 3.0.1", "# PlexonPanel 3.0.2", 1)
readme = readme.replace(
    "Version 3.0.1 makes every currently implemented Paper and Host capability locally enable-able",
    "Version 3.0.2 hardens relay/agent lifecycle recovery while keeping every currently implemented Paper and Host capability locally enable-able",
    1,
)
p.write_text(readme)

p = Path("docs/PROTOCOL.md")
docs = p.read_text().replace(
    "Product/bundle version 3.0.1, wire version 3.",
    "Product/bundle version 3.0.2, wire version 3.",
    1,
)
note = '''\n## 3.0.2 lifecycle rules\n\nPaper remains authoritative for full access synchronization. It publishes `access.sync` after a newly authenticated relay session and after real local access mutations. A Dashboard `gateway.snapshot_request` refreshes telemetry/console snapshots only and does not re-run access synchronization.\n\nHost reads the shared registry but does not publish a full access snapshot merely because it reconnects or emits a service-status sample. Host-side device revocation continues to publish the resulting removal through the shared `ControlEngine`.\n\nPaper WebSocket close/error/ping/send callbacks are scoped to the socket that produced them so stale callbacks from an older transport epoch cannot tear down a newer authenticated session. Protocol wire version remains 3.\n'''
if "## 3.0.2 lifecycle rules" not in docs:
    docs += note
p.write_text(docs)

p = Path("CHANGELOG.md")
changelog = p.read_text()
entry = '''## 3.0.2 — reliability candidate, live lifecycle acceptance pending\n\n- Stop Host reconnect/service polling from publishing unconditional full `access.sync` snapshots.\n- Keep Host access publication mutation-driven through authorized local revocation.\n- Separate Paper authenticated-session initialization from Dashboard snapshot refresh so browser reconnects do not re-sync access.\n- Scope Paper ping/send/close/error recovery to the socket that actually failed and preserve bounded reconnect/session reset behavior.\n- Extend `/ppanel diagnostics` with reconnect, session-prefix, protocol-code and critical-queue visibility without printing secrets.\n- Make the PlexonCraft full-control preset pair new intended operators as Owner while preserving `Owner = Scopes.ALL` and local capability intersection.\n- Mark Java SIGTERM (143) as a successful intentional Host systemd stop while preserving `Restart=on-failure` and `RestartSec=5`.\n- Preserve protocol v3, identities, the shared access registry and existing immutable grants.\n\n'''
if not changelog.startswith("## 3.0.2"):
    changelog = entry + changelog
p.write_text(changelog)
