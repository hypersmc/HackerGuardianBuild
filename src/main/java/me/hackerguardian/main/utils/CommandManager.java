package me.hackerguardian.main.utils;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * @author JumpWatch on 28-03-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class CommandManager implements CommandExecutor {
    public final textHandling tx = new textHandling();
    public final Map<String, BiConsumer<CommandSender, String[]>> registeredCommands = new LinkedHashMap<>();

    public CommandManager(Plugin plugin, String... baseCommands) {
        if (plugin == null || baseCommands == null) return;

        for (String baseCommand : baseCommands) {
            if (baseCommand == null || baseCommand.isBlank()) continue;
            PluginCommand command = plugin.getServer().getPluginCommand(baseCommand);
            if (command == null) {
                plugin.getLogger().warning("Command '" + baseCommand + "' is not declared in plugin.yml");
                continue;
            }
            command.setExecutor(this);
        }
    }

    public void register(String command, BiConsumer<CommandSender, String[]> event) {
        if (command == null || event == null) return;
        registeredCommands.put(command.toLowerCase().trim(), event);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 && registeredCommands.containsKey("")) {
            registeredCommands.get("").accept(sender, args);
            return true;
        }

        String fullExecution = String.join(" ", args).toLowerCase();

        Optional<Map.Entry<String, BiConsumer<CommandSender, String[]>>> matchedCommand =
                registeredCommands.entrySet().stream()
                        .filter(entry -> !entry.getKey().isEmpty())
                        .filter(entry -> fullExecution.equals(entry.getKey())
                                || fullExecution.startsWith(entry.getKey() + " "))
                        .max((a, b) -> Integer.compare(a.getKey().length(), b.getKey().length()));

        if (matchedCommand.isPresent()) {
            Map.Entry<String, BiConsumer<CommandSender, String[]>> match = matchedCommand.get();
            int consumedArgs = match.getKey().split("\\s+").length;
            String[] params = Arrays.copyOfRange(args, Math.min(consumedArgs, args.length), args.length);
            match.getValue().accept(sender, params);
        } else {
            sender.sendMessage(tx.playerText(tx.prefix + "Command not found!"));
        }
        return true;
    }
}
