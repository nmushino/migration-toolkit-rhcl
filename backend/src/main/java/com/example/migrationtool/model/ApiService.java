package com.example.migrationtool.model;

import java.util.List;

public class ApiService {
    public String id;
    public String name;
    public String description;
    public String state;
    public String systemName;
    public String backendVersion;
    public String deploymentOption;

    public List<Backend> backends;
    public List<MappingRule> mappingRules;
    public List<Metric> metrics;
    public List<Policy> policies;
    public Authentication authentication;
    public String proxyEndpoint;
    public String apicastProductionEndpoint;
    public String apicastStagingEndpoint;
}
