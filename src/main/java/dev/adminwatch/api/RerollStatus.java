package dev.adminwatch.api;

public enum RerollStatus {
    SUCCESSFUL("Successful"), PARTIAL("Partial"), FAILED("Failed");

    private final String label;

    RerollStatus(String label) { this.label = label; }

    public String label() { return label; }
}
