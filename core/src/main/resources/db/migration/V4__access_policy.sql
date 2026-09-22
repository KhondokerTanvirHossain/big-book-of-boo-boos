-- Issue #7: AccessPolicy storage. Not a HAPI JPA resource — served by a plain IResourceProvider from this
-- table, as ADR-001 and ADR-003 decide for the Medplum admin types. HAPI JPA has no DAO for a custom
-- @ResourceDef type on 8.12.1 (HAPI-0572, spiked on #7), so a table is the only honest option.

CREATE TABLE access_policy (
    id          uuid PRIMARY KEY,
    -- tenancy: every policy belongs to exactly one project, and a policy is only ever loaded for a
    -- membership of that same project. This column is the whole of the isolation for this table.
    project_id  uuid NOT NULL REFERENCES project (id),
    name        text NOT NULL,
    -- the Medplum AccessPolicy resource as sent, kept whole: resource[], compartment, basedOn, ipAccessRule.
    -- Compiled on every request, never trusted as pre-validated — see PolicyBinder's fail-closed path.
    document    jsonb NOT NULL,
    version_id  text NOT NULL,
    last_updated timestamptz NOT NULL DEFAULT now()
);

-- the per-request lookup: a membership's policy references, scoped to its project. The project_id is first
-- so the index cannot be used to reach a policy from outside the project that owns it.
CREATE INDEX access_policy_project ON access_policy (project_id, id);

-- Medplum allows two policies with the same name in one project; nothing here forbids it. The name is a
-- label, not a key: attachments reference the id.
