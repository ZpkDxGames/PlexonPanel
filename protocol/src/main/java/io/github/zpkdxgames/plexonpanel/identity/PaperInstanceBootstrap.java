package io.github.zpkdxgames.plexonpanel.identity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/** Public preflight performed before Paper configuration or identity publication. */
public final class PaperInstanceBootstrap {
  private PaperInstanceBootstrap() {}

  public static FleetIdentity validate(Path serverRoot, Path dataDirectory, String account,
      int minecraftPort, NodeInstanceRegistry.Registry registry, UUID nodeId) throws IOException {
    if (serverRoot == null || !serverRoot.isAbsolute() || !serverRoot.equals(serverRoot.normalize())
        || serverRoot.getNameCount() != 5 || !serverRoot.getParent().getParent().equals(Path.of("/srv/plexonpanel/servers"))
        || !serverRoot.getFileName().toString().equals("server"))
      throw new IOException("PAPER_INSTANCE_ROOT_INVALID");
    var layout = new InstanceLayout(serverRoot.getParent().getFileName().toString());
    InstanceLayout.requirePath(serverRoot.toString(), layout.serverRoot());
    InstanceLayout.requirePath(dataDirectory.toString(), layout.serverRoot().resolve("plugins/PlexonPanel"));
    InstanceLayout.rejectSymlinks(serverRoot);
    InstanceLayout.rejectSymlinks(dataDirectory);
    if (!layout.minecraftUser().equals(account)) throw new IOException("PAPER_INSTANCE_ACCOUNT_MISMATCH");
    if (!registry.nodeId().equals(nodeId)) throw new IOException("PAPER_NODE_IDENTITY_MISMATCH");
    var entry = registry.instance(layout.instanceKey());
    if (entry.minecraftPort() != minecraftPort) throw new IOException("REGISTERED_MINECRAFT_PORT_MISMATCH");
    return entry.identity(nodeId, layout.instanceKey());
  }
}
