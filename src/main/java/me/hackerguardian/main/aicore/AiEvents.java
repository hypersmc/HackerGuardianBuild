package me.hackerguardian.main.aicore;

import me.hackerguardian.main.HackerGuardian;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;

/**
 * @author JumpWatch on 19-04-2024
 * @Project HackerGuardian
 * v1.0.0
 */
public class AiEvents implements Listener {

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();

        // Extract relevant information from the player movement event
        double[] input = extractInputFromPlayerMoveEvent(player, from, to);

        // Pass the input to the anti-cheat system for learning
        HackerGuardian.getAi().learnFromEvent(input);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        // Extract relevant information from the player interaction event
        double[] input = extractInputFromPlayerInteractEvent(event);

        // Pass the input to the anti-cheat system for learning
        HackerGuardian.getAi().learnFromEvent(input);
    }

    @EventHandler
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        // Extract relevant information from the player chat event
        double[] input = extractInputFromPlayerChatEvent(event);

        // Pass the input to the anti-cheat system for learning
        HackerGuardian.getAi().learnFromEvent(input);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Material blockType = event.getBlockPlaced().getType();

        // Extract relevant information from the block placement event
        double[] input = extractInputFromBlockPlaceEvent(event);

        // Pass the input to the anti-cheat system for learning
        HackerGuardian.getAi().learnFromEvent(input);
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent) {
            EntityDamageByEntityEvent damageEvent = (EntityDamageByEntityEvent) event;
            Player attacker = null;
            if (damageEvent.getDamager() instanceof Player) {
                attacker = (Player) damageEvent.getDamager();
            }
            Player victim = null;
            if (event.getEntity() instanceof Player) {
                victim = (Player) event.getEntity();
            }

            // Extract relevant information from the combat event
            double[] input = extractInputFromCombatEvent(attacker, victim, event.getDamage());

            // Pass the input to the anti-cheat system for learning
            HackerGuardian.getAi().learnFromEvent(input);
        }
    }

    // Additional methods for extracting information from events

    private double[] extractInputFromPlayerMoveEvent(Player player, Location from, Location to) {
        // Initialize array to hold the extracted information
        double[] input = new double[10]; // Assuming 9 inputs based on the provided event information

        // Extract relevant information from the event
        // Example: X, Y, Z coordinates
        double fromX = from.getX();
        double fromY = from.getY();
        double fromZ = from.getZ();
        double toX = to.getX();
        double toY = to.getY();
        double toZ = to.getZ();

        // Example: Velocity
        double velocityX = toX - fromX;
        double velocityY = toY - fromY;
        double velocityZ = toZ - fromZ;

        // Example: Direction (could be simplified based on your requirements)
        double direction = Math.atan2(toZ - fromZ, toX - fromX);

        // Example: Jumping status (1 if jumping, 0 if not)
        boolean isJumping = player.getVelocity().getY() >= 0 && !player.isOnGround() || player.isFlying(); // You can adjust this based on your requirements
        double jumpingStatus = isJumping ? 1.0 : 0.0;

        // Example: Sneaking status (1 if sneaking, 0 if not)
        boolean isSneaking = player.isSneaking();
        double sneakingStatus = isSneaking ? 1.0 : 0.0;

        // Example: Sprinting status (1 if sprinting, 0 if not)
        boolean isSprinting = player.isSprinting();
        double sprintingStatus = isSprinting ? 1.0 : 0.0;

        // Populate the input array with extracted information
        input[0] = fromX;
        input[1] = fromY;
        input[2] = fromZ;
        input[3] = velocityX;
        input[4] = velocityY;
        input[5] = velocityZ;
        input[6] = direction;
        input[7] = jumpingStatus;
        input[8] = sneakingStatus;
        input[9] = sprintingStatus;

        return input;
    }

    private double[] extractInputFromPlayerInteractEvent(PlayerInteractEvent event) {
        // Get the player involved in the event
        Player player = event.getPlayer();

        // Get the item the player is interacting with
        ItemStack item = event.getItem();

        // Extract relevant information from the player and the item
        double playerHealth = player.getHealth();
        double playerFoodLevel = player.getFoodLevel();
        double itemDurability = (item != null) ? item.getDurability() : 0.0;

        // You can add more relevant information here based on your requirements

        // Return the extracted information as an array
        return new double[]{playerHealth, playerFoodLevel, itemDurability};
    }

    public static double[] extractInputFromPlayerChatEvent(PlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        // Extract relevant information from the event
        double[] input = new double[5]; // Adjust the size based on the number of inputs

        // Example: Player's health
        double health = player.getHealth();
        input[0] = health;

        // Example: Length of the chat message
        int messageLength = message.length();
        input[1] = messageLength;

        // You can add more logic to extract other relevant information here

        return input;
    }

    private double[] extractInputFromBlockPlaceEvent(BlockPlaceEvent event) {
        // Extract relevant information from the event
        Player player = event.getPlayer();
        Material blockType = event.getBlockPlaced().getType();

        // Example: Extracting player's coordinates
        double playerX = player.getLocation().getX();
        double playerY = player.getLocation().getY();
        double playerZ = player.getLocation().getZ();

        // Example: Encoding block type (you can adjust this encoding based on your requirements)
        int encodedBlockType = encodeBlockType(blockType);

        // Create the input array with relevant information
        double[] input = {
                playerX, playerY, playerZ, encodedBlockType
                // Add more relevant information as needed
        };

        return input;
    }

    private int encodeBlockType(Material blockType) {
        // Encode block type to a numerical value (example)
        // You can define your own encoding scheme based on your requirements
        switch (blockType) {
            case STONE:
                return 0;
            case DIRT:
                return 1;
            case GRASS_BLOCK:
                return 2;
            // Add more cases for other block types as needed
            default:
                return -1; // Unknown block type
        }
    }

    private double[] extractInputFromCombatEvent(Player attacker, Player victim, double damage) {
        // Define the number of relevant inputs
        int numInputs = 5; // Assuming you want to include attacker and victim IDs, attacker health, victim health, and damage

        // Create an array to hold the input data
        double[] input = new double[numInputs];

        // Extract relevant information from the event
        input[0] = attacker.getUniqueId().hashCode(); // Attacker's unique ID
        input[1] = victim.getUniqueId().hashCode(); // Victim's unique ID
        input[2] = attacker.getHealth(); // Attacker's health
        input[3] = victim.getHealth(); // Victim's health
        input[4] = damage; // Damage inflicted

        return input;
    }

}