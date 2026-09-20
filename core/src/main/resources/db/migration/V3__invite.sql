-- Issue #6: what the invite and client routes add to a membership.

ALTER TABLE project_membership
    -- `User` | `ClientApplication` | `Bot`; for clients and bots user === profile (T7)
    ADD COLUMN user_type  text NOT NULL DEFAULT 'User',
    -- scope `server` (many projects) or `project` (this one only) — BB-R-005.2
    ADD COLUMN user_scope text NOT NULL DEFAULT 'server',
    ADD COLUMN invited_by_membership uuid REFERENCES project_membership (id);

-- T6: the same email may not be both server- and project-scoped in one project. The unique key on
-- (project_id, email) already forbids two rows; this makes the scope of the one row explicit.
ALTER TABLE project_membership
    ADD CONSTRAINT project_membership_user_scope CHECK (user_scope IN ('server', 'project')),
    ADD CONSTRAINT project_membership_user_type CHECK (user_type IN ('User', 'ClientApplication', 'Bot'));
