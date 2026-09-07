package io.github.zpkdxgames.plexonpanel.ui;

import io.github.zpkdxgames.plexonpanel.PlexonPanelPlugin;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

/**
 * A holder-owned, read-only inventory. Transitions run on the next tick after click cancellation.
 */
public final class AdminGui implements Listener {
  private final PlexonPanelPlugin plugin;

  public AdminGui(PlexonPanelPlugin plugin) {
    this.plugin = plugin;
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
  }

  private static final class Menu implements InventoryHolder {
    private Inventory inventory;
    private final Map<Integer, String> actions = new HashMap<>();
    private String confirm;
    private long expiry;

    public Inventory getInventory() {
      return inventory;
    }
  }

  private Menu menu(String title) {
    var m = new Menu();
    m.inventory = Bukkit.createInventory(m, 54, Component.text(title, NamedTextColor.AQUA));
    button(m, 48, Material.ARROW, "Control room", "home", List.of());
    button(m, 49, Material.BARRIER, "Close", "close", List.of());
    return m;
  }

  public void open(Player player) {
    if (!player.hasPermission("plexonpanel.gui") || plugin.runtime() == null) return;
    var r = plugin.runtime();
    var m = menu("PlexonPanel · Control room");
    button(
        m,
        13,
        Material.BEACON,
        "Agent status",
        "status",
        List.of(
            "Relay: " + r.gateway().state(),
            "Protocol: 3 · Version: 3.0.0",
            "Identity: " + plugin.identity().fingerprint().substring(0, 17)));
    button(
        m,
        20,
        Material.TRIPWIRE_HOOK,
        "Pair a device",
        "roles 0",
        List.of("Choose a local role", "One-use code · 5 minute expiry"));
    button(
        m,
        22,
        Material.COMPARATOR,
        "Remote capabilities",
        "capabilities 0",
        List.of("View locally enabled scopes", "Edit config.yml to change policy"));
    button(
        m,
        24,
        Material.PLAYER_HEAD,
        "Paired devices",
        "devices 0",
        List.of("View and revoke device access"));
    button(
        m, 31, Material.SPYGLASS, "Diagnostics", "diagnostics", List.of("Safe connection summary"));
    player.openInventory(m.inventory);
  }

  private void pages(Menu m, String view, int page, int total) {
    if (page > 0)
      button(m, 45, Material.ARROW, "Previous page", view + " " + (page - 1), List.of());
    if ((page + 1) * 45 < total)
      button(m, 53, Material.ARROW, "Next page", view + " " + (page + 1), List.of());
  }

  private void roles(Player player, int page) {
    if (!player.hasPermission("plexonpanel.pair")) return;
    var m = menu("PlexonPanel · Pair device");
    var roles = new ArrayList<>(plugin.runtime().policy().roles().keySet());
    for (int i = page * 45; i < Math.min(roles.size(), (page + 1) * 45); i++) {
      String role = roles.get(i);
      button(
          m,
          i % 45,
          role.equals("Owner") ? Material.NETHER_STAR : Material.TRIPWIRE_HOOK,
          role,
          "pair " + role,
          List.of(
              role.equals("Observer")
                  ? "Read-only monitoring"
                  : "Privileged role · check granted scopes",
              "Click to generate a local code"));
    }
    pages(m, "roles", page, roles.size());
    player.openInventory(m.inventory);
  }

  private void devices(Player player, int page) {
    if (!player.hasPermission("plexonpanel.devices")) return;
    var runtime = plugin.runtime();
    plugin
        .getServer()
        .getScheduler()
        .runTaskAsynchronously(
            plugin,
            () -> {
              try {
                var devices = runtime.devices().snapshot().devices();
                plugin
                    .getServer()
                    .getScheduler()
                    .runTask(
                        plugin,
                        () -> {
                          if (!player.isOnline() || plugin.runtime() != runtime) return;
                          var m = menu("PlexonPanel · Devices");
                          for (int i = page * 45;
                              i < Math.min(devices.size(), (page + 1) * 45);
                              i++) {
                            var d = devices.get(i);
                            button(
                                m,
                                i % 45,
                                Material.PLAYER_HEAD,
                                d.name() + " · " + d.role(),
                                "revoke " + d.deviceId(),
                                List.of(
                                    d.deviceId(),
                                    "Click twice to revoke",
                                    "Expiry: " + java.time.Instant.ofEpochSecond(d.expiresAt())));
                          }
                          pages(m, "devices", page, devices.size());
                          player.openInventory(m.inventory);
                        });
              } catch (Exception e) {
                plugin.getLogger().warning("Device GUI could not read local access state");
              }
            });
  }

  private void capabilities(Player player, int page) {
    if (!player.hasPermission("plexonpanel.capabilities")) return;
    var m = menu("PlexonPanel · Capabilities");
    var entries =
        plugin.runtime().policy().capabilities().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .toList();
    for (int i = page * 45; i < Math.min(entries.size(), (page + 1) * 45); i++) {
      var e = entries.get(i);
      button(
          m,
          i % 45,
          e.getValue() ? Material.LIME_DYE : Material.GRAY_DYE,
          e.getKey(),
          "",
          List.of(e.getValue() ? "Locally enabled" : "Locally disabled"));
    }
    pages(m, "capabilities", page, entries.size());
    player.openInventory(m.inventory);
  }

  private void button(
      Menu m, int slot, Material material, String title, String action, List<String> lore) {
    var item = new ItemStack(material);
    var meta = item.getItemMeta();
    meta.displayName(Component.text(title, NamedTextColor.AQUA));
    meta.lore(lore.stream().map(s -> Component.text(s, NamedTextColor.GRAY)).toList());
    item.setItemMeta(meta);
    m.inventory.setItem(slot, item);
    m.actions.put(slot, action);
  }

  @EventHandler
  public void click(InventoryClickEvent event) {
    if (!(event.getView().getTopInventory().getHolder() instanceof Menu m)) return;
    event.setCancelled(true);
    if (!(event.getWhoClicked() instanceof Player player)
        || event.getRawSlot() < 0
        || event.getRawSlot() >= 54
        || !event.isLeftClick()) return;
    String action = m.actions.get(event.getRawSlot());
    if (action == null || action.isEmpty()) return;
    plugin
        .getServer()
        .getScheduler()
        .runTask(
            plugin,
            () -> {
              if (!player.isOnline()
                  || !player.hasPermission("plexonpanel.gui")
                  || plugin.runtime() == null
                  || player.getOpenInventory().getTopInventory().getHolder() != m) return;
              if (action.startsWith("roles ")) {
                roles(player, Integer.parseInt(action.substring(6)));
                return;
              }
              if (action.startsWith("devices ")) {
                devices(player, Integer.parseInt(action.substring(8)));
                return;
              }
              if (action.startsWith("capabilities ")) {
                capabilities(player, Integer.parseInt(action.substring(13)));
                return;
              }
              if (action.equals("home")) {
                open(player);
                return;
              }
              if (action.equals("close")) {
                player.closeInventory();
                return;
              }
              if (action.startsWith("revoke ")) {
                if (!player.hasPermission("plexonpanel.revoke")) return;
                if (!action.equals(m.confirm) || System.currentTimeMillis() > m.expiry) {
                  m.confirm = action;
                  m.expiry = System.currentTimeMillis() + 10000;
                  player.sendMessage(
                      Component.text(
                          "Click this device again within 10 seconds to revoke it.",
                          NamedTextColor.GOLD));
                  return;
                }
              }
              player.closeInventory();
              player.performCommand("plexonpanel " + action);
            });
  }

  @EventHandler
  public void drag(InventoryDragEvent event) {
    if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
  }
}
