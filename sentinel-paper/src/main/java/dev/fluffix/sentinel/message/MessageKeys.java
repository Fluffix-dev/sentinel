package dev.fluffix.sentinel.message;

public enum MessageKeys {
    PREFIX,
    NO_PERMISSION,
    REASONS_ADDED,
    REASONS_REMOVED,
    REASONS_HEADER,
    REASONS_LINE,
    RELOAD_DONE;

    public String key() {
        return name().toLowerCase(); // "prefix", "no_permission", ...
    }
}
