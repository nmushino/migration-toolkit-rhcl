package com.redhat.migrationtoolkit.rhcl.service;

import com.redhat.migrationtoolkit.rhcl.client.ThreeScaleClient;
import com.redhat.migrationtoolkit.rhcl.dto.ConnectionRequest;
import com.redhat.migrationtoolkit.rhcl.model.ApiService;
import com.redhat.migrationtoolkit.rhcl.model.Authentication;
import com.redhat.migrationtoolkit.rhcl.model.Backend;
import com.redhat.migrationtoolkit.rhcl.model.MappingRule;
import com.redhat.migrationtoolkit.rhcl.model.Metric;
import com.redhat.migrationtoolkit.rhcl.model.Policy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ThreeScaleExportService {

    public boolean testConnection(ConnectionRequest req) {
        try {
            ThreeScaleClient client = buildClient(req.url);
            Map<String, Object> result = client.getServices(req.accessToken, 1, 1);
            return result != null;
        } catch (Exception e) {
            return false;
        }
    }

    public List<ApiService> exportServices(String url, String accessToken) {
        ThreeScaleClient client = buildClient(url);
        List<ApiService> services = new ArrayList<>();

        try {
            Map<String, Object> response = client.getServices(accessToken, 1, 500);
            List<Map<String, Object>> serviceList = extractList(response, "services");

            for (Map<String, Object> svcWrapper : serviceList) {
                Map<String, Object> svc = (Map<String, Object>) svcWrapper.get("service");
                if (svc == null) {
                    continue;
                }

                ApiService service = new ApiService();
                service.id = String.valueOf(svc.get("id"));
                service.name = (String) svc.get("name");
                service.description = (String) svc.get("description");
                service.state = (String) svc.get("state");
                service.systemName = (String) svc.get("system_name");
                service.backendVersion = (String) svc.get("backend_version");
                service.deploymentOption = (String) svc.get("deployment_option");

                service.policies = fetchPolicies(client, service.id, accessToken);
                service.mappingRules = fetchMappingRules(client, service.id, accessToken);
                service.metrics = fetchMetrics(client, service.id, accessToken);
                service.authentication = extractAuthentication(svc);
                service.backends = fetchBackendsForService(client, service.id, accessToken);

                Map<String, Object> proxyConfig = safeGetProxyConfig(client, service.id, accessToken);
                if (proxyConfig != null) {
                    service.proxyEndpoint = extractProxyEndpoint(proxyConfig);
                }

                services.add(service);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to export services from 3scale: " + e.getMessage(), e);
        }

        return services;
    }

    public ApiService exportService(String url, String accessToken, String serviceId) {
        ThreeScaleClient client = buildClient(url);
        Map<String, Object> response = client.getService(serviceId, accessToken);
        Map<String, Object> svc = (Map<String, Object>) response.get("service");

        ApiService service = new ApiService();
        service.id = String.valueOf(svc.get("id"));
        service.name = (String) svc.get("name");
        service.description = (String) svc.get("description");
        service.systemName = (String) svc.get("system_name");
        service.backendVersion = (String) svc.get("backend_version");
        service.deploymentOption = (String) svc.get("deployment_option");
        service.policies = fetchPolicies(client, serviceId, accessToken);
        service.mappingRules = fetchMappingRules(client, serviceId, accessToken);
        service.metrics = fetchMetrics(client, serviceId, accessToken);
        service.authentication = extractAuthentication(svc);
        service.backends = fetchBackendsForService(client, serviceId, accessToken);

        return service;
    }

    private List<Policy> fetchPolicies(ThreeScaleClient client, String serviceId, String accessToken) {
        try {
            Map<String, Object> resp = client.getPolicies(serviceId, accessToken);
            List<Map<String, Object>> policyList = extractList(resp, "policies_config");
            List<Policy> policies = new ArrayList<>();
            for (Map<String, Object> p : policyList) {
                Policy policy = new Policy();
                policy.name = (String) p.get("name");
                policy.version = (String) p.get("version");
                policy.enabled = (Boolean) p.getOrDefault("enabled", true);
                policy.configuration = (Map<String, Object>) p.get("configuration");
                policies.add(policy);
            }
            return policies;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<MappingRule> fetchMappingRules(ThreeScaleClient client, String serviceId, String accessToken) {
        try {
            Map<String, Object> resp = client.getMappingRules(serviceId, accessToken);
            List<Map<String, Object>> ruleList = extractList(resp, "mapping_rules");
            List<MappingRule> rules = new ArrayList<>();
            for (Map<String, Object> rw : ruleList) {
                Map<String, Object> r = (Map<String, Object>) rw.get("mapping_rule");
                if (r == null) {
                    r = rw;
                }
                MappingRule rule = new MappingRule();
                rule.id = String.valueOf(r.get("id"));
                rule.httpMethod = (String) r.get("http_method");
                rule.pattern = (String) r.get("pattern");
                rule.metricSystemName = (String) r.get("metric_system_name");
                rule.last = (Boolean) r.getOrDefault("last", false);
                rules.add(rule);
            }
            return rules;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<Metric> fetchMetrics(ThreeScaleClient client, String serviceId, String accessToken) {
        try {
            Map<String, Object> resp = client.getMetrics(serviceId, accessToken);
            List<Map<String, Object>> metricList = extractList(resp, "metrics");
            List<Metric> metrics = new ArrayList<>();
            for (Map<String, Object> mw : metricList) {
                Map<String, Object> m = (Map<String, Object>) mw.get("metric");
                if (m == null) {
                    m = mw;
                }
                Metric metric = new Metric();
                metric.id = String.valueOf(m.get("id"));
                metric.name = (String) m.get("friendly_name");
                metric.systemName = (String) m.get("system_name");
                metric.unit = (String) m.get("unit");
                metrics.add(metric);
            }
            return metrics;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<Backend> fetchBackendsForService(ThreeScaleClient client, String serviceId, String accessToken) {
        try {
            Map<String, Object> resp = client.getBackends(accessToken, 1, 500);
            List<Map<String, Object>> backendList = extractList(resp, "backends");
            List<Backend> backends = new ArrayList<>();
            for (Map<String, Object> bw : backendList) {
                Map<String, Object> b = (Map<String, Object>) bw.get("backend_api");
                if (b == null) {
                    continue;
                }
                Backend backend = new Backend();
                backend.id = String.valueOf(b.get("id"));
                backend.name = (String) b.get("name");
                backend.systemName = (String) b.get("system_name");
                backend.privateEndpoint = (String) b.get("private_endpoint");
                backends.add(backend);
            }
            return backends;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private Authentication extractAuthentication(Map<String, Object> svc) {
        Authentication auth = new Authentication();
        String backendVersion = (String) svc.get("backend_version");
        if ("1".equals(backendVersion)) {
            auth.type = "apiKey";
        } else if ("2".equals(backendVersion)) {
            auth.type = "appIdKey";
        } else if ("oidc".equals(backendVersion)) {
            auth.type = "jwt";
        } else {
            auth.type = "none";
        }
        return auth;
    }

    private Map<String, Object> safeGetProxyConfig(ThreeScaleClient client, String serviceId, String accessToken) {
        try {
            return client.getProxyConfig(serviceId, accessToken);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractProxyEndpoint(Map<String, Object> proxyConfig) {
        try {
            Map<String, Object> proxyConfigObj = (Map<String, Object>) proxyConfig.get("proxy_config");
            Map<String, Object> content = (Map<String, Object>) proxyConfigObj.get("content");
            Map<String, Object> proxy = (Map<String, Object>) content.get("proxy");
            return (String) proxy.get("endpoint");
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractList(Map<String, Object> response, String key) {
        Object val = response.get(key);
        if (val instanceof List) {
            return (List<Map<String, Object>>) val;
        }
        return Collections.emptyList();
    }

    private ThreeScaleClient buildClient(String baseUrl) {
        try {
            return RestClientBuilder.newBuilder()
                    .baseUri(new URI(baseUrl))
                    .build(ThreeScaleClient.class);
        } catch (Exception e) {
            throw new RuntimeException("Invalid 3scale URL: " + baseUrl, e);
        }
    }
}
