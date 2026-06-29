package com.example.migrationtool.model;

public class CompatibilityItem {
    public String name;
    public String status; // SUPPORTED, WARNING, UNSUPPORTED
    public String message;

    public CompatibilityItem() {}

    public CompatibilityItem(String name, String status, String message) {
        this.name = name;
        this.status = status;
        this.message = message;
    }
}
