package io.github.zpkdxgames.plexonpanel.bedrock;

import java.time.Instant;

record BedrockLogLine(Instant capturedAt, String stream, String content, boolean truncated) {}
