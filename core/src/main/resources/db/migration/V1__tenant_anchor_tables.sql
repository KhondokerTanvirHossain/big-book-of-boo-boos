-- The rows that anchor cross-store provisioning (ADR-007). Issue #3 creates them for the
-- super-admin project; issue #4 grows them into the full Project / ProjectMembership model.
-- Flyway's default schema is `bigbook`, so unqualified names land there.

CREATE TABLE project (
    id           uuid PRIMARY KEY,
    -- HAPI partition ids are integers; the partition's name is this row's id
    partition_id integer GENERATED ALWAYS AS IDENTITY UNIQUE,
    name         text        NOT NULL,
    super_admin  boolean     NOT NULL DEFAULT false,
    status       text        NOT NULL CHECK (status IN ('provisioning', 'active')),
    created_at   timestamptz NOT NULL DEFAULT now()
);

-- at most one super-admin project; this is also what makes bootstrap idempotent
CREATE UNIQUE INDEX project_single_super_admin ON project (super_admin) WHERE super_admin;

CREATE TABLE project_membership (
    id         uuid PRIMARY KEY,
    project_id uuid        NOT NULL REFERENCES project (id),
    -- the stable key a retried invite resumes on
    email      text        NOT NULL,
    -- Keycloak user id (token `sub`); null until the user exists
    user_id    text,
    -- FHIR reference to the profile resource; set from issue #4 on, when partitions are in the request path
    profile    text,
    admin      boolean     NOT NULL DEFAULT false,
    status     text        NOT NULL CHECK (status IN ('provisioning', 'active')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (project_id, email)
);
