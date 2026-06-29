-- Hibernate の PanacheEntity が参照するシーケンス名に合わせる
CREATE SEQUENCE IF NOT EXISTS project_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS conversion_history_seq START WITH 1 INCREMENT BY 50;
