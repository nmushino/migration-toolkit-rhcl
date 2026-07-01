package com.example.migrationtool.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "conversion_history")
public class ConversionHistoryEntity extends PanacheEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    public ProjectEntity project;

    @Column(name = "service_id")
    public String serviceId;

    @Column(name = "service_name")
    public String serviceName;

    public String status; // COMPLETED, FAILED, IN_PROGRESS

    @Column(name = "compatibility_score")
    public Integer compatibilityScore;

    @Column(name = "yaml_content", columnDefinition = "TEXT")
    public String yamlContent;

    /** CONVERT（変換フロー）または IMPORT（ZIP インポート） */
    @Column(name = "source", length = 20)
    public String source = "CONVERT";

    @Column(name = "namespace")
    public String namespace;

    @Column(name = "total_count")
    public Integer totalCount;

    @Column(name = "success_count")
    public Integer successCount;

    @Column(name = "failure_count")
    public Integer failureCount;

    /** JSON: [{fileName, resourceKind, resourceName, error}] */
    @Column(name = "failure_details", columnDefinition = "TEXT")
    public String failureDetails;

    /** JSON: {filename: yamlContent} — cluster から export した実リソース YAML */
    @Column(name = "exported_yaml", columnDefinition = "TEXT")
    public String exportedYaml;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();

    public static ConversionHistoryEntity findLatestByServiceId(String serviceId) {
        return find("serviceId = ?1 ORDER BY createdAt DESC", serviceId).firstResult();
    }
}
