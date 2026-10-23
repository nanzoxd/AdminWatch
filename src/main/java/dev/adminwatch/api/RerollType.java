package dev.adminwatch.api;

public enum RerollType {
    INVENTORY("Inventory"), ENDER_CHEST("Ender Chest");

    private final String label;

    RerollType(String label) { this.label = label; }

    public String label() { return label; }
}
