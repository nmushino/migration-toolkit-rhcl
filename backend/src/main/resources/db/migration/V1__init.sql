CREATE TABLE project (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    threescale_url VARCHAR(1000),
    tenant VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE conversion_history (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT REFERENCES project(id),
    service_id VARCHAR(255),
    service_name VARCHAR(255),
    status VARCHAR(50),
    compatibility_score INTEGER,
    yaml_content TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_conversion_history_project ON conversion_history(project_id);
CREATE INDEX idx_conversion_history_created ON conversion_history(created_at DESC);
