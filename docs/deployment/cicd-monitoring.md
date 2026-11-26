# HelpDesk CI/CD and local monitoring

> 상태 갱신: 2026-11-26. 아래 설계 기록 중 “미적용”으로 쓰였던 OIDC/IAM 설명은 뒤이은 AWS 설정 완료 전의 기록입니다. 현재는 별도 게시·배포 역할, `production` Environment, Custom SSM Document v1이 구성되어 최종 승인 배포까지 실행됐습니다. 세부 실행 증빙은 공개 GitHub Actions 기록과 비공개 운영 보고서에 보존합니다.

## Implemented CI contract

`.github/workflows/ci.yml` runs on pushes and pull requests targeting `main`:

1. Java 21 full test suite against an ephemeral PostgreSQL service. `TICKET_IT_DB_URL` enables `TicketRepositoryPostgresIntegrationTest`; a separate assertion fails CI unless all 10 tests ran, none skipped, and none failed.
2. Existing Python evaluator unit tests and Gradle `bootJar`.
3. Frontend `npm ci` and production build.
4. Release rollback/failure-injection suite on an ARM64 GitHub-hosted runner with native ARM64 PostgreSQL fixtures. This matches the deployment scripts' ARM64 image guard; the suite does not contact AWS.
5. Backend and frontend `linux/arm64` Buildx validation without push.
6. Isolated Playwright suite (10 tests). The deterministic mock is used. The RAG browser regression remains in PR CI, while `E2E_RUN_K6=0` leaves the 40-second, 1/5/10/20-VU load profile to deliberate local runs.
7. JUnit skip/failure summary and upload of JUnit/E2E result files. CI artifacts are retained 14 days.

Check GitHub Actions against the exact pushed commit SHA before claiming remote CI success. CI checks Java, Python, frontend, browser, release recovery, and ARM64 build contracts; a successful CI run is not an AWS deployment or runtime verification.

## Manual release stages

No push or pull request can publish or deploy. Both workflows are `workflow_dispatch` only and require the selected ref to be `main`.

### Publish

`HelpDesk release publish` requires a successful `ci.yml` run for the exact current commit. It creates one filtered, deterministic source snapshot, verifies it is clean and tied to `GITHUB_SHA`, computes the runtime operations bundle hash, and builds/pushes each ARM64 image once. The tag is unique (`release-<commit-sha>-<run-id>`) to avoid collisions with immutable ECR tags. The resulting ECR digests are recorded in `release-manifest.json` together with the source archive SHA-256, Git commit, PostgreSQL digest, and operations bundle SHA-256. The manifest and source archive are kept as a 90-day workflow artifact. No image is rebuilt for deployment.

Required repository variables:

- `AWS_ACCOUNT_ID`
- `ECR_BACKEND_REPOSITORY` = `ACCOUNT_ID.dkr.ecr.ap-southeast-2.amazonaws.com/helpdesk-backend`
- `ECR_FRONTEND_REPOSITORY` = `ACCOUNT_ID.dkr.ecr.ap-southeast-2.amazonaws.com/helpdesk-frontend`
- `AWS_ECR_PUBLISH_ROLE_ARN`
- `HELPDESK_POSTGRES_IMAGE` = exact digest-pinned PostgreSQL image already used on the instance
- For deployment: `AWS_DEPLOY_ROLE_ARN`, `AWS_INSTANCE_ID`

The publisher role has only ECR login plus push actions scoped to the two repositories. It cannot send SSM commands or read application parameters.

### Deploy

After reviewing the publish run's manifest, manually start `HelpDesk approved deployment` with its publish run ID. The pre-deployment job confirms that the source run succeeded on `main`, checks the manifest commit and release tag against that exact workflow run, recomputes the downloaded snapshot archive SHA-256, checks its embedded commit/clean worktree, and publishes the image digests and source hashes in the job summary. The deployment job uses the `production` GitHub environment, assumes a separate short-lived OIDC role, and invokes the versioned `HelpDesk-DeployRelease` Custom SSM Document v1 for the existing EC2. It passes only validated release values; it does not submit arbitrary shell commands through `AWS-RunShellScript`. The document calls the root-owned host deployment scripts. The release retains the pinned PostgreSQL image and data, and the workflow polls SSM status. No SSH port or public app port is used.

The workflow deliberately checks the installed `/opt/helpdesk` operations files with `ops-bundle-hash.py` before recording/deploying a release. The latest local scripts and `compose.aws.yaml` are not automatically copied to EC2 by this workflow. The check fails closed until an operator has installed the exact approved runtime bundle and confirmed its hash through the existing AWS operations change procedure. This is required because an image-only rollout would leave old host scripts/configuration in effect.

`deploy/aws/scripts/ops-bundle-hash.py --root .` prints the bundle hash locally. Run the same helper on the host after updating `/opt/helpdesk`; compare the 64-hex result with the release manifest's `opsBundleSha256`. Do not replace `/etc/helpdesk/aws-deploy.env`; it contains host-specific non-secret settings and parameter names. The deployment workflow does not retrieve, display, or change Parameter Store values.

## OIDC, IAM and approval setup (applied)

Templates are in `deploy/aws/iam/`:

- `github-actions-ecr-trust.template.json` and `github-actions-ecr-publish-policy.template.json`
- `github-actions-deploy-trust.template.json` and `github-actions-deploy-policy.template.json`

The approved AWS setup uses separate ECR publisher and SSM deploy roles, a GitHub OIDC provider, and the `production` environment. The trust scopes the publisher to the repository's protected `main` ref and deploy access to the repository's `production` environment subject. The deploy workflow is manually dispatched from `main`; its environment protection requires reviewer approval when a deployment job enters the protected environment. No long-lived AWS access key is used. The deployed SSM document is the fixed `HelpDesk-DeployRelease` version 1; the GitHub deploy role can invoke it for the designated instance but cannot edit or delete the document. The existing EC2 instance role is a separate principal and was not expanded as part of GitHub OIDC setup.

The deploy role is intentionally separate from the ECR role. The fixed document constrains accepted release inputs and calls the host's root-owned deployment scripts; the role has no SSM document update/delete permission. This narrows the workflow's SSM command surface compared with arbitrary `AWS-RunShellScript`, while the document and privileged host scripts remain security-critical and must be reviewed together when changed.

The `production` Environment's required reviewer protection was configured and used for the final deployment. This is a deployment gate; a single-person project does not establish an independent second-person review. See [GitHub environment protection](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments).

The earlier effective-policy review found a discrepancy between IAM simulation and user-reported instance behavior for Parameter Store reads. That issue is separate from the GitHub OIDC roles and was not resolved by widening the instance role or bypassing an Organizations policy. Do not infer effective instance permissions from the GitHub role setup; re-check the instance role, KMS conditions, and any SCP only as a separately approved read-only/operational review.

## Metrics and alerts

Existing instrumented data is reused:

- Spring Boot Actuator/Micrometer/Prometheus registry.
- Automatic HTTP server, JVM, and Hikari connection-pool metrics.
- `TokenMeterAdvisor`: AI call latency and prompt/completion token counts when the provider returns usage metadata.
- `ToolAuditAspect`: bounded `tool` and `result` labels for call count and latency.
- `RagIndexReadiness`: optional-profile one-hot gauges for the finite readiness states.
- A five-second bounded sampler of the existing readiness group, which includes DB health.

`application-monitoring.yml` places metrics on management port 8081, enables Prometheus scrape and HTTP/AI/tool histograms, and remains inactive by default. `compose.monitoring.yaml` is a local-only overlay; Prometheus, Grafana, and the test Alertmanager/webhook sink publish only to `127.0.0.1`. The AWS Compose does not include the overlay, profile, management port, or monitoring containers. The regular application port does not expose the Prometheus endpoint. The local dashboard shows HTTP throughput/5xx/P95, core and AI readiness, tools, token use, JVM heap, and Hikari connections.

The local monitoring overlay is intentionally not an AWS Compose profile. Its declared container memory caps total about 3.1 GiB including the disposable AI mock, before the host OS and Docker overhead, and its CPU caps total about 5 vCPU. That exceeds the current 2 GiB `t4g.small` instance budget. Do not enable it on that host; the local profile is a reproducibility and behavior test, not a production sizing recommendation.

Local verification command (requires Docker, k6 is optional):

```bash
E2E_MONITORING=1 E2E_RUN_K6=0 node e2e/run.mjs
```

This uses a unique Compose project, generated test-only passwords, the deterministic OpenAI-compatible test double, PostgreSQL with a disposable project volume, and a loopback Grafana password. The Playwright AI-failure/recovery test checks the RAG gauge and local firing/resolved webhook. The runner stops and restarts only its own PostgreSQL container, checks the core readiness gauge and alert transition, and records `monitoring-verification.json`. It then cleans only its own Compose project/volume. No external email, Slack, CloudWatch, or public metrics endpoint is configured.

Prometheus alerts are short local demonstration rules, not production SLOs. The RAG alert fires only on `UNAVAILABLE`; a deliberately unconfigured optional AI service is not treated as an outage. The readiness sampler makes one additional health/DB probe every five seconds while the optional profile is active. Do not enable it on AWS without a resource budget and network review.

## Cost, state and remaining verification

- No AWS monitoring service, EC2 container, CloudWatch log group, alarm, or network endpoint is added. Thus this change adds no recurring AWS monitoring charge.
- Publishing adds private ECR image storage; AWS charges for stored private image data. Same-region EC2 pulls avoid ECR-to-EC2 transfer charges under AWS's published pricing examples. Keep an image-retention policy and remove old digests only in a separately approved cleanup. [Amazon ECR pricing](https://aws.amazon.com/ecr/pricing/).
- GitHub Actions runner minutes and artifacts may consume included or billable usage depending on public/private repository and plan. CI artifacts retain 14 days; release artifacts retain 90 days. [GitHub Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions).
- Prometheus/Grafana/Alertmanager run only on local Docker for demonstration, not AWS.
- AWS Systems Manager Run Command has no additional charge on EC2 instances; ECR private image storage remains the material release-related AWS charge. [Systems Manager pricing](https://aws.amazon.com/systems-manager/pricing/).
- Current local verification run (2026-11-25): Java 169 tests with 162 passed, 7 skipped, 0 failures; PostgreSQL integration 10/10 executed; Python 33/33; Playwright 10/10; release failure injection 11/11 cases. Monitoring evidence is under `artifacts/e2e/runs/<run-id>/`; skipped test names and reasons remain in JUnit XML.
- The final AWS deployment was separately verified through the approved deploy workflow; this section's local monitoring profile was not installed in AWS. The final AWS run confirmed service health and selected application/data checks, but did not exercise the manual old-Compose recovery procedure. That fallback requires restoring the old application image together with its compatible Compose/operations bundle; PostgreSQL image and data remain outside app rollback. See the final deployment report for the exact scope and evidence.
