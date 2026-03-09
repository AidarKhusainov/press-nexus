# Contracts

- OpenAPI: `openapi.yaml`
- Event schemas: `events/*.schema.json`
- Versioning policy: `versioning-policy.md`
- Generated HTTP API/controllers: `app/target/generated-sources/openapi`
- Contract tests:
  - `app/src/test/java/com/nexus/press/app/web/ApiContractTest.java`
  - `app/src/test/java/com/nexus/press/app/contract/ContractArtifactsContractTest.java`

## Contract-First HTTP Workflow

1. Change `openapi.yaml`.
2. Regenerate Spring API/controllers:
   - `./scripts/generate-openapi.sh`
3. Implement endpoint behavior in controllers under:
   - `app/src/main/java/com/nexus/press/app/controller`
4. Run contract checks:
   - `./scripts/verify-contracts.sh`
   - `CONTRACT_BASE_REF=origin/main ./scripts/check-contract-breaking.sh`

Do not manually edit generated files under `app/target/generated-sources/openapi`.
Do not use OpenAPI generator `importMappings` for API schemas: DTO must come from `openapi.yaml`.
All API/event changes must update contracts and matching contract tests.
