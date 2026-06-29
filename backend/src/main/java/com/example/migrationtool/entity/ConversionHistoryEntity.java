package com.example.migrationtool.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
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

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();

    public static ConversionHistoryEntity findLatestByServiceId(String serviceId) {
        return find("serviceId = ?1 ORDER BY createdAt DESC", serviceId).firstResult();
    }
}
