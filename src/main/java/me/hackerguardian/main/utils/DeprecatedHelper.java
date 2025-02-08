package me.hackerguardian.main.utils;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * @author JumpWatch on 17-04-2024
 * @Project HackerGuardian
 * v1.0.0
 */
public class DeprecatedHelper {

    /**
     * Marks a specific block of code as deprecated.
     * @param block The block of code to be marked as deprecated.
     * @param reason The reason for deprecating the block of code.
     */
    public static void markDeprecated(Runnable block, String reason) {
        System.out.println("This block of code is deprecated: " + reason);
        block.run();
    }
    /**
     * Marks a specific block of code as deprecated.
     * @param block The block of code to be marked as deprecated.
     * @param reason The reason for deprecating the block of code.
     */
    public static void markDeprecated(Consumer<Void> block, String reason) {
        System.out.println("This block of code is deprecated: " + reason);
        block.accept(null);
    }
    /**
     * Marks a specific block of code as deprecated.
     * @param block The block of code to be marked as deprecated.
     * @param reason The reason for deprecating the block of code.
     * @return The result of the deprecated block.
     */
    public static boolean markDeprecated(Supplier<Boolean> block, String reason) {
        System.out.println("This block of code is deprecated: " + reason);
        return block.get();
    }
}
