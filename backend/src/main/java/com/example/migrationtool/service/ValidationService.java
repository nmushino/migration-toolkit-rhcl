package com.example.migrationtool.service;

import com.example.migrationtool.dto.ValidationResult;
import com.example.migrationtool.dto.ValidationResult.ValidationItem;
import jakarta.enterprise.context.ApplicationScoped;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class ValidationService {

    private static final Set<String> KNOWN_CRDS = Set.of(
            "gateway.networking.k8s.io/v1",
            "gateway.networking.k8s.io/v1beta1",
            "kuadrant.io/v1",
            "kuadrant.io/v1beta2",
            "kuadrant.io/v1alpha1",
            "networking.istio.io/v1alpha3",
            "networking.istio.io/v1beta1",
            "networking.istio.io/v1",
            "v1"
    );

    public ValidationResult validate(Map<String, String> yamlFiles) {
        ValidationResult result = new ValidationResult();
        result.items = new ArrayList<>();

        for (Map.Entry<String, String> entry : yamlFiles.entrySet()) {
            String filename = entry.getKey();
            String content = entry.getValue();

            if (!filename.endsWith(".yaml")) {
                continue;
            }

            result.items.addAll(validateYamlSyntax(filename, content));
            result.items.addAll(validateCrd(filename, content));
            result.items.addAll(validateNamespace(filename, content));
            result.items.addAll(validateReferences(filename, content, yamlFiles));
        }

        result.valid = result.items.stream().noneMatch(i -> "ERROR".equals(i.status));
        return result;
    }

    /** YAML を --- で分割して各ドキュメントをリストで返す。loadAll() でマルチドキュメントを処理。 */
    private List<Map<String, Object>> loadAllDocs(String content) {
        Yaml yaml = new Yaml();
        List<Map<String, Object>> docs = new ArrayList<>();
        for (Object obj : yaml.loadAll(content)) {
            if (obj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> doc = (Map<String, Object>) obj;
                docs.add(doc);
            }
        }
        return docs;
    }

    private List<ValidationItem> validateYamlSyntax(String filename, String content) {
        try {
            List<Map<String, Object>> docs = loadAllDocs(content);
            int count = docs.size();
            String detail = count > 1
                    ? "Valid YAML syntax (" + count + " documents)" : "Valid YAML syntax";
            return List.of(new ValidationItem("YAML Syntax: " + filename, "OK", detail));
        } catch (Exception e) {
            return List.of(new ValidationItem(
                    "YAML Syntax: " + filename, "ERROR", "Invalid YAML: " + e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private List<ValidationItem> validateCrd(String filename, String content) {
        try {
            List<Map<String, Object>> docs = loadAllDocs(content);
            List<ValidationItem> items = new ArrayList<>();
            for (Map<String, Object> doc : docs) {
                String apiVersion = (String) doc.get("apiVersion");
                if (apiVersion == null) {
                    continue;
                }
                boolean known = KNOWN_CRDS.stream().anyMatch(apiVersion::startsWith);
                if (known) {
                    items.add(new ValidationItem("CRD: " + apiVersion, "OK", "Known CRD group"));
                } else {
                    items.add(new ValidationItem("CRD: " + apiVersion, "WARNING",
                            "Unknown CRD - verify it is installed in the cluster"));
                }
            }
            return items;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private List<ValidationItem> validateNamespace(String filename, String content) {
        try {
            List<Map<String, Object>> docs = loadAllDocs(content);
            List<ValidationItem> items = new ArrayList<>();
            for (Map<String, Object> doc : docs) {
                Map<String, Object> metadata = (Map<String, Object>) doc.get("metadata");
                if (metadata == null) {
                    continue;
                }
                String kind = (String) doc.get("kind");
                String label = kind != null ? filename + " (" + kind + ")" : filename;
                String ns = (String) metadata.get("namespace");
                if (ns == null || ns.isBlank()) {
                    items.add(new ValidationItem("Namespace: " + label, "WARNING",
                            "No namespace set, will use default namespace"));
                } else {
                    items.add(new ValidationItem("Namespace: " + label, "OK", "Namespace: " + ns));
                }
            }
            return items;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private List<ValidationItem> validateReferences(String filename, String content,
                                                     Map<String, String> allFiles) {
        List<ValidationItem> items = new ArrayList<>();
        try {
            for (Map<String, Object> doc : loadAllDocs(content)) {
                String kind = (String) doc.get("kind");

                if ("HTTPRoute".equals(kind)) {
                    Map<String, Object> spec = (Map<String, Object>) doc.get("spec");
                    if (spec != null) {
                        List<Map<String, Object>> parentRefs =
                                (List<Map<String, Object>>) spec.get("parentRefs");
                        if (parentRefs != null) {
                            for (Map<String, Object> ref : parentRefs) {
                                String refName = (String) ref.get("name");
                                boolean gatewayExists = allFiles.containsKey("gateway.yaml");
                                if (gatewayExists) {
                                    items.add(new ValidationItem(
                                            "Reference: Gateway " + refName, "OK",
                                            "Referenced Gateway found in package"));
                                } else {
                                    items.add(new ValidationItem(
                                            "Reference: Gateway " + refName, "WARNING",
                                            "Referenced Gateway not in package - ensure it exists in cluster"));
                                }
                            }
                        }
                    }
                }

                if ("AuthPolicy".equals(kind)) {
                    boolean httprouteExists = allFiles.containsKey("httproute.yaml");
                    if (httprouteExists) {
                        items.add(new ValidationItem(
                                "Reference: AuthPolicy -> HTTPRoute", "OK",
                                "HTTPRoute found in package"));
                    } else {
                        items.add(new ValidationItem(
                                "Reference: AuthPolicy -> HTTPRoute", "WARNING",
                                "HTTPRoute not in package"));
                    }
                }
            }

            if (content.contains("REPLACE_ME")) {
                items.add(new ValidationItem("Secret Values: " + filename, "WARNING",
                        "Contains placeholder values - update before applying"));
            }

        } catch (Exception e) {
            // ignore parse errors, already caught in syntax check
        }
        return items;
    }
}
