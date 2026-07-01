package com.redhat.migrationtoolkit.rhcl.model;

import java.util.Map;

public class Policy {
    public String name;
    public String version;
    public Boolean enabled;
    public Map<String, Object> configuration;
}
