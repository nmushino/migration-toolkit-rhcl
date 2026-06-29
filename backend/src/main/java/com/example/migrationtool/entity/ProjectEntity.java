package com.example.migrationtool.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "project")
public class ProjectEntity extends PanacheEntity {

    @Column(nullable = false)
    public String name;

    @Column(name = "threescale_url", length = 1000)
    public String threescaleUrl;

    public String tenant;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt = LocalDateTime.now();

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    public List<ConversionHistoryEntity> histories;

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
