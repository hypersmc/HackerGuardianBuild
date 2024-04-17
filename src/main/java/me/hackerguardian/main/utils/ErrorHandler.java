package me.hackerguardian.main.utils;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author JumpWatch on 11-12-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class ErrorHandler {
    private static final Logger logger = Logger.getLogger(ErrorHandler.class.getName());

    public static void handleIOException(IOException e, String message) {
        logger.log(Level.SEVERE, message, e);
        // Additional handling or actions for IOException
    }

    public static void handleSecurityException(SecurityException e, String message) {
        logger.log(Level.WARNING, message, e);
        // Additional handling or actions for SecurityException
    }

    // More methods for handling other specific exceptions...

    public static void handleGenericException(Exception e, String message) {
        logger.log(Level.SEVERE, message, e);
        // Additional handling or actions for generic exceptions
    }
}
