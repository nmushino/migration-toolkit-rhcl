package com.example.migrationtool.dto;

import java.util.List;

public class ConversionRequest {
    public String threescaleUrl;
    public String accessToken;
    public String tenant;
    public String namespace;
    public List<String> serviceIds;
}
