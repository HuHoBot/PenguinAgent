package agent.spigot.scripting;

import agent.spigot.scripting.CommandCallback;
import agent.spigot.scripting.TabCompleteCallback;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

public class BirdDynamicCommand extends Command {

    private final CommandCallback callback;
    private TabCompleteCallback tabCompleteCallback;

    public BirdDynamicCommand(String name, CommandCallback callback) {
        super(name);
        this.callback = callback;
        this.setDescription("BirdLibraryApi script command: /" + name);
    }

    public void setTabCompleteCallback(TabCompleteCallback tabCompleteCallback) {
        this.tabCompleteCallback = tabCompleteCallback;
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (getPermission() != null && !getPermission().isEmpty() && !sender.hasPermission(getPermission())) {
            sender.sendMessage(getPermissionMessage() != null
                    ? getPermissionMessage()
                    : "§cYou don't have permission to use this command");
            return true;
        }
        try {
            callback.execute(sender, label, args);
        } catch (Exception e) {
            sender.sendMessage("§cScript error: " + e.getMessage());
            e.printStackTrace();
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (tabCompleteCallback == null) {
            return new ArrayList<>();
        }
        try {
            List<String> result = tabCompleteCallback.execute(sender, alias, args);
            return result != null ? result : new ArrayList<>();
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
}
