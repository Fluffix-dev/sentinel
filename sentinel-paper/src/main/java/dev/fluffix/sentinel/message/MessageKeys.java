package dev.fluffix.sentinel.message;

public enum MessageKeys {
    PREFIX,
    NO_PERMISSION,
    REASONS_ADDED,
    REASONS_REMOVED,
    REASONS_HEADER,
    REASONS_LINE,
    RELOAD_DONE,

    BAN_KICK,
    BAN_LIST_USAGE,
    BAN_LIST_HEADER,
    BAN_LIST_LINE,
    BAN_LIST_EMPTY,
    UNBAN_USAGE,
    UNBAN_SUCCESS,
    UNBAN_NOT_FOUND,

    BAN_USAGE,
    BAN_SUCCESS,
    BAN_ERROR,
    BAN_SQL_ERROR;

    public String key() {
        return name().toLowerCase();
    }
}
