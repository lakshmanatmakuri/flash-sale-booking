CREATE TABLE users (
                       id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                       email         VARCHAR(255) NOT NULL,
                       password_hash VARCHAR(255) NOT NULL,
                       full_name     VARCHAR(150) NOT NULL,
                       status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
                       created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
                       updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
                       CONSTRAINT uq_users_email UNIQUE (email),
                       CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'BLOCKED'))
);

CREATE TABLE roles (
                       id   SMALLINT    PRIMARY KEY,
                       name VARCHAR(30) NOT NULL,
                       CONSTRAINT uq_roles_name UNIQUE (name)
);

CREATE TABLE user_roles (
                            user_id UUID     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
                            role_id SMALLINT NOT NULL REFERENCES roles (id),
                            PRIMARY KEY (user_id, role_id)
);

CREATE INDEX idx_user_roles_role ON user_roles (role_id);

INSERT INTO roles (id, name) VALUES
                                 (1, 'USER'),
                                 (2, 'ORGANIZER'),
                                 (3, 'ADMIN');