package dev.fluffix.sentinel.logger;

import java.util.HashMap;
import java.util.Map;

public class PluginLogger {

    private static final String RESET = "\u001B[0m";

    private static final Map<String, String> COLORS = new HashMap<>();

    static {
        COLORS.put("BLACK", "\u001B[30m");
        COLORS.put("RED", "\u001B[31m");
        COLORS.put("GREEN", "\u001B[32m");
        COLORS.put("YELLOW", "\u001B[33m");
        COLORS.put("BLUE", "\u001B[34m");
        COLORS.put("PURPLE", "\u001B[35m");
        COLORS.put("CYAN", "\u001B[36m");
        COLORS.put("WHITE", "\u001B[37m");
        COLORS.put("GRAY", "\u001B[90m");
    }

    public static void print(String message, String colorName) {
        String colorCode = COLORS.getOrDefault(colorName.toUpperCase(), RESET);
        System.out.println(colorCode + message + RESET);
    }

    public static void printWithLabel(String label, String message, String colorName) {
        String colorCode = COLORS.getOrDefault(colorName.toUpperCase(), RESET);
        System.out.println(colorCode + "[" + label.toUpperCase() + "] " + message + RESET);
    }
}
