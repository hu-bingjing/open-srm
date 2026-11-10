# OpenSRM project rules

- Read `docs/plan.md` and the owning design before changing behavior; report actual phase status.
- Preserve generic naming and virtual sample data. No real organization identifiers, addresses, credentials or business documents in publishable files.
- Core modules cannot import vendor adapters. ERP receipts are authoritative; ASN never creates receipts.
- Every business write requires authorization, scope filtering, version checks, transactional audit and idempotency.
- Do not connect to production ERP, deploy to production, publish the repository, select a license or delete original data without explicit authorization.
- Treat `specs/` as executable design examples, not an application backend. Port acceptance cases to JUnit/Testcontainers as modules are implemented.
- Run README checks after changes. No broad uploads, no generated artifacts in commits.
