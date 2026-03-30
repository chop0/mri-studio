package ax.xz.mri.ui.workbench;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** Simple registry for shell- and pane-level commands. */
public class CommandRegistry {
    private final Map<CommandId, PaneAction> actions = new EnumMap<>(CommandId.class);

    public void register(PaneAction action) {
        actions.put(action.id(), action);
    }

    public Optional<PaneAction> lookup(CommandId id) {
        return Optional.ofNullable(actions.get(id));
    }

    public void execute(CommandId id) {
        lookup(id).ifPresent(PaneAction::run);
    }
}
