package dev.adminwatch.model;

/** Audit categories. Each one maps to its own webhook section in config.yml. */
public enum Category {
    COMMANDS("commands", "ADMIN COMMAND EXECUTED", 0x3498DB),
    CREATIVE_ITEMS("creative_items", "ADMIN CREATIVE ITEM", 0x9B59B6),
    BLOCK_CHANGES("block_changes", "ADMIN BLOCK CHANGE", 0xE67E22),
    COMMAND_BLOCKS("command_blocks", "ADMIN COMMAND BLOCK ACTIVITY", 0xE74C3C),
    INVENTORY_INSPECTIONS("inventory_inspections", "ADMIN INVENTORY INSPECTION", 0x1ABC9C),
    INVENTORY_MODIFICATIONS("inventory_modifications", "ADMIN INVENTORY MODIFICATION", 0xF1C40F),
    INVENTORY_REROLLS("inventory_rerolls", "ADMIN INVENTORY REROLL", 0x2ECC71);

    private final String key;
    private final String title;
    private final int defaultColor;

    Category(String key, String title, int defaultColor) {
        this.key = key;
        this.title = title;
        this.defaultColor = defaultColor;
    }

    /** Key used in config.yml and the database. */
    public String key() { return key; }
    public String title() { return title; }
    public int defaultColor() { return defaultColor; }

    public static Category fromKey(String key) {
        for (Category c : values()) if (c.key.equalsIgnoreCase(key)) return c;
        return null;
    }
}
