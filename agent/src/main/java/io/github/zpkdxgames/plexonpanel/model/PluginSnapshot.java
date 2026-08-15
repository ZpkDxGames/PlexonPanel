package io.github.zpkdxgames.plexonpanel.model;

import java.util.List;

public record PluginSnapshot(
    String name,
    String version,
    String mainClass,
    List<String> authors,
    String website,
    boolean enabled
) {
}
