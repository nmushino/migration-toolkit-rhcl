package com.redhat.migrationtoolkit.rhcl.model;

import java.util.List;

public class CompatibilityResult {
    public String serviceId;
    public String serviceName;
    public int score;
    public String level; // HIGH, MEDIUM, LOW
    public List<CompatibilityItem> items;
}
