package io.github.zpkdxgames.plexonpanel.host;

import io.github.zpkdxgames.plexonpanel.identity.InstanceLayout;
import java.util.List;

/** Local immutable journal authority; browser filters cannot select a namespace or unit. */
record JournalSource(String unit, String namespace) {
  JournalSource {
    if (unit == null || !unit.matches("[A-Za-z0-9][A-Za-z0-9_.@-]{0,90}\\.service"))
      throw new IllegalArgumentException("JOURNAL_UNIT_INVALID");
    if (namespace != null) {
      if (!unit.startsWith("minecraft@")) throw new IllegalArgumentException("JOURNAL_NAMESPACE_MISMATCH");
      var layout = new InstanceLayout(unit.substring("minecraft@".length(), unit.length() - ".service".length()));
      if (!namespace.equals(layout.journalNamespace()))
        throw new IllegalArgumentException("JOURNAL_NAMESPACE_MISMATCH");
    }
  }

  List<String> arguments() {
    return namespace == null ? List.of("--unit", unit)
        : List.of("--unit", unit, "--namespace=" + namespace);
  }
}
