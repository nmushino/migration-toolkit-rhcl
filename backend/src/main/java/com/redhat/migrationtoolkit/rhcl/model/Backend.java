package com.redhat.migrationtoolkit.rhcl.model;

import java.util.List;

public class Backend {
    public String id;
    public String name;
    public String description;
    public String systemName;
    public String privateEndpoint;
    public List<MappingRule> mappingRules;
    public List<Metric> metrics;
}
