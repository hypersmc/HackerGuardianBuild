package me.hackerguardian.main.utils;
import me.hackerguardian.main.HackerGuardian;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
/**
 * @author JumpWatch on 28-03-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class CommandManager implements CommandExecutor {
    public textHandling text = new textHandling();
    public Map<String, BiConsumer<CommandSender, String[]>> registeredCommands = new HashMap<>();

    public CommandManager(Plugin plugin, String baseCommand) {
        if (plugin != null)
            plugin.getServer().getPluginCommand(baseCommand).setExecutor(this);
    }

    public void register(String comand, BiConsumer<CommandSender, String[]> event) {
        registeredCommands.put(comand.toLowerCase(), event);
    }
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 && registeredCommands.containsKey("")) {
            registeredCommands.get("").accept(sender, args);
            return true;
        }

        String fullExecution = String.join(" ", args).toLowerCase();
        Optional<Map.Entry<String, BiConsumer<CommandSender, String[]>>> matchedCommand =
                registeredCommands.entrySet().stream().filter(entry -> !entry.getKey().equals("")).filter(entry -> fullExecution.startsWith(entry.getKey())).findAny();
        if (matchedCommand.isPresent()) {
            String[] param = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, matchedCommand.get().getKey().split(" ").length, args.length);
            matchedCommand.get().getValue().accept(sender, param);
        }else
            sender.sendMessage(text.prefix + "Command not found!");
        return true;
    }
}

