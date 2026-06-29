package com.example.migrationtool.model;

import java.util.List;

public class Project {
    public Long id;
    public String name;
    public String threescaleUrl;
    public String tenant;
    public List<ApiService> services;
    public List<Backend> backends;
    public List<Route> routes;
    public List<Policy> policies;
    public List<Metric> metrics;
    public List<Authentication> authentications;
    public List<MappingRule> mappingRules;
}
