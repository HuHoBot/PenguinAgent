package agent.spigot.scripting;

import org.bukkit.command.CommandSender;

@FunctionalInterface
public interface CommandCallback {
    void execute(CommandSender sender, String label, String[] args);
}
