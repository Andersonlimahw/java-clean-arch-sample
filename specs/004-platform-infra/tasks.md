# Tasks: Platform Infrastructure

- [x] T001 Spec, plan, tasks
- [x] T002 [P] `Dockerfile` (multi-stage, layered, non-root, artifact-agnostic) + `.dockerignore`
- [x] T003 [P] `docker-compose.yml` + `.env.example` + `scripts/generate-jwt-keys.sh`
- [x] T004 [P] Kustomize base (Deployment, Service, ConfigMap, HPA, PDB, Ingress, NetworkPolicy, migration Job, ServiceAccount) + `secret.example.yaml`
- [x] T005 Overlays `staging` and `production` (namespace, replicas, resources, hosts, issuers)
- [x] T006 [P] `.github/workflows/ci.yml`
- [x] T007 [P] `scripts/ci-local.sh`
- [x] T008 [P] `railway.json`, `render.yaml`, `deploy/cloudrun/service.yaml`
- [x] T009 README "Platform & CI" section + `deploy/k8s/README.md`
- [x] T010 Validate: compose config, kustomize + kubeconform (strict, 1.30), hadolint, `bash -n`, image build on current `main`
- [x] T011 After PR1 merge: rebase, image build, `docker compose up` readiness smoke (Compose stack + API smoke recorded on `main` for PR #18, see README demo; `scripts/validate-api.sh` polls readiness)
- [x] T012 PR1/PR8: wire `app.migration-only=true` (run Flyway, then `SpringApplication.exit`)
