package io.github.zpkdxgames.plexonpanel.protocol;

public interface MessageSink {
  boolean send(String type, Object body, MessagePriority priority);
}
