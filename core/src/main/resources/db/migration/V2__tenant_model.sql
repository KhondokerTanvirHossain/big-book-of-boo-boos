-- Issue #4: the tenant model on top of #3's anchor rows.

-- Medplum-shaped Project fields other than id and name, as written by the admin routes:
-- checkReferencesOnWrite, defaultProfile[], features[], defaultAccessPolicies[], setting[], secret[]
-- (BB-R-005.6). `secret` is plaintext and admin-readable in lite, as in Medplum (D35).
ALTER TABLE project ADD COLUMN settings jsonb NOT NULL DEFAULT '{}';

ALTER TABLE project_membership
    ADD COLUMN access_policy      text,
    ADD COLUMN access             jsonb NOT NULL DEFAULT '[]',
    ADD COLUMN user_configuration text,
    ADD COLUMN invited_by         text;

-- BB-R-005.15: Keycloak matches emails case-insensitively, so one user must not get two seats by case.
UPDATE project_membership SET email = lower(email);
ALTER TABLE project_membership ADD CONSTRAINT project_membership_email_lowercase CHECK (email = lower(email));

-- the per-request lookup: caller's seat by (project, Keycloak sub)
CREATE INDEX project_membership_project_user ON project_membership (project_id, user_id);
