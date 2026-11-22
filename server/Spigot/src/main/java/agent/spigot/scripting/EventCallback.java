package agent.spigot.scripting;

import org.bukkit.event.Event;

@FunctionalInterface
public interface EventCallback {
    void execute(Event event);
}
