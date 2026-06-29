package com.example.migrationtool.model;

import java.util.List;

public class Route {
    public String id;
    public String name;
    public String path;
    public String method;
    public String backendId;
    public String backendPath;
    public List<Policy> policies;
}
