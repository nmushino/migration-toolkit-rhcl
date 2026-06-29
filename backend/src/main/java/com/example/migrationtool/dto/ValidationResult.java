package com.example.migrationtool.dto;

import java.util.List;

public class ValidationResult {
    public boolean valid;
    public List<ValidationItem> items;

    public static class ValidationItem {
        public String check;
        public String status; // OK, WARNING, ERROR
        public String message;

        public ValidationItem(String check, String status, String message) {
            this.check = check;
            this.status = status;
            this.message = message;
        }
    }
}
