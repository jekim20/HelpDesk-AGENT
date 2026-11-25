# AWS ARM64 deployment operations

This guide prepares a private, single-instance demo in `ap-southeast-2`. It does not create AWS resources. Do not run any AWS command below until the resource list and estimated spend have been approved.

## Deployment files

- `compose.aws.yaml` runs only digest-pinned images. It never builds from source. PostgreSQL data is bind-mounted from the dedicated EBS mount; the app and database have no host-published ports; Nginx is bound to `127.0.0.1`.
- `deploy/aws/scripts/record-release.sh` records three immutable image references into a mode-0600 release manifest.
- `prepare-host.sh` verifies ARM64, Compose, the EBS mount and its encryption, PostgreSQL directory ownership, SSM parameters, and Compose configuration. It never formats a disk or deletes data.
- `deploy.sh` pulls and validates the candidate, takes an S3 backup of the existing DB, and checks container health, image identity, and the frontend route before publishing the active manifest.
- `rollback.sh` activates and verifies the prior app and frontend images while retaining the PostgreSQL container. It can replace an unhealthy app when the PostgreSQL container is healthy and the existing image references match the active manifest. Both scripts use the same recovery procedure when activation fails.
- `backup-postgres.sh` writes a custom-format `pg_dump` file and uploads it to the configured S3 prefix with SSE-S3. `--local-only PATH` is available for isolated local backup testing; it refuses to overwrite a file.
- `restore-test-postgres.sh BACKUP` restores into a unique, new database and verifies ticket counts, PENDING counts, idempotency-key counts, and the unique constraint. It never drops a database. A failed restore database is intentionally left for inspection.
- `verify-persistence.sh` compares ticket counts and a ticket/key fingerprint before and after PostgreSQL, app, and frontend restarts.
- `stop-services.sh` stops Compose without deleting the EBS data. EC2, EBS, S3, ECR, and snapshots can continue to incur charges.
- `cost-check.sh` is a read-only AWS inventory for resources tagged `Project=helpdesk-ai-agent`; it cannot find untagged resources, so check the Billing console too.

All shell scripts disable tracing. They do not print parameter values. Application secrets are read at runtime from SSM Parameter Store `SecureString` through the instance role and exported only to the Compose process. Docker administrators can still see container environment values using Docker inspection; restrict host access to the operator.

## AWS resources and settings to approve

1. One EC2 `t4g.small` running Amazon Linux 2023 ARM64 in `ap-southeast-2`, with CPU credit mode `standard` for predictable spend. The OS/root EBS volume can be deleted with the instance; PostgreSQL data is on the separate data volume below.
2. One encrypted gp3 EBS data volume (20 GiB initial proposal), attached separately and tagged `Project=helpdesk-ai-agent`. Keep it after instance termination (`DeleteOnTermination=false`). Snapshot retention is a separate cost and must be selected explicitly.
3. One security group with **no ingress rules**. Allow TCP 443 egress for SSM, ECR, S3, package updates, and later explicitly approved HTTPS AI calls. Do not expose ports 22, 5432, or 8080.
4. One instance profile with `deploy/aws/iam/instance-role-policy.template.json` narrowed to the actual account, ECR repos, parameter ARNs, backup bucket/prefix, and KMS context. It grants no EC2 create/modify/terminate or IAM permissions.
5. Two private ECR repositories (`helpdesk-backend`, `helpdesk-frontend`) with tag immutability enabled. ECR stores the ARM64 image artifacts; runtime references use manifest digests. Keep at least the current and previous release.
6. One private S3 backup bucket in Sydney (`ap-southeast-2`) with Block Public Access, SSE-S3 default encryption, unique timestamped object keys, and an agreed lifecycle (initial proposal: 30 days). The host role can read/write only `helpdesk/postgres/*`; it cannot delete backups.
7. Four required SSM SecureString parameters for DB and demo account passwords. An OpenAI key parameter is optional and must not be created or loaded until Live AI/RAG use is separately approved. Use the AWS-managed `alias/aws/ssm` key to avoid creating a billable customer KMS key unless customer-managed key control is required.
8. No NAT Gateway, load balancer, RDS, static Elastic IP, or VPC interface endpoints in the initial low-cost topology. Use an automatically assigned public IPv4 in a public subnet, with inbound blocked, for outbound service access. Stop the instance when not testing; automatic public IPv4 is released when stopped.

The operator policy template is deliberately separate from the instance role. Scope `StartSession` to this instance and the `AWS-StartPortForwardingSession` document. The own-session ARN pattern in the template must be validated for the actual IAM Identity Center principal before use. Require MFA. Session Manager port forwarding is encrypted, but AWS does not log the forwarded application payload.

### EBS filesystem

Identify the attached data disk by its EBS volume serial in `lsblk -o NAME,SERIAL,FSTYPE,MOUNTPOINTS`. Never select a disk by guessed `/dev/nvme*` ordering. Formatting is destructive: only an operator who has verified the new volume ID, empty filesystem, and encryption may create a filesystem. The scripts intentionally do not run `mkfs`.

Mount the verified filesystem at `/data`, configure its UUID in `/etc/fstab`, then create `/data/postgres`. Set `HELPDESK_PGDATA_PATH` to that directory and `HELPDESK_EBS_MOUNT` to the mount point. `prepare-host.sh` checks that the Postgres path resolves to that mounted volume and fails closed if the disk is absent. Install `deploy/aws/systemd/docker.service.d/20-helpdesk-ebs.conf` as `/etc/systemd/system/docker.service.d/20-helpdesk-ebs.conf`, then run `systemctl daemon-reload`; this makes Docker wait for the data mount at boot so PostgreSQL cannot silently initialize on the root disk if the EBS mount is missing.

## First deployment sequence (after approval)

1. Confirm the approved account and region; create/tag the resources above. Record the EC2 instance ID, EBS volume ID, ECR repository URIs, S3 bucket, and SecureString parameter names in `/etc/helpdesk/aws-deploy.env` based on `deploy/aws/config.example.env`. The file contains names and IDs only, never values of secrets.
2. Attach the encrypted data EBS and mount it persistently. Install/enable Docker Engine, Docker Compose v2, AWS CLI v2, and the SSM Agent on AL2023 using AWS's current Amazon Linux instructions. Confirm SSM reports the node online before opening a local tunnel.
3. Build and publish the frontend and backend for `linux/arm64` to the two immutable ECR repositories, tagged with the Git commit SHA. Resolve the manifest digests from ECR. Resolve the official `postgres:17-alpine` ARM64 image digest too. Do not deploy mutable `latest` tags.
4. Copy `compose.aws.yaml` and scripts to `/opt/helpdesk`; install scripts executable. Create `/etc/helpdesk/releases/` and `/var/lib/helpdesk/` with mode 0700. Record exact digests with `record-release.sh`. Keep the first manifest in the releases directory until deployment publishes it.
5. Create the SecureString parameters from the AWS console or an approved secure workflow; do not put secret values in shell history, instance user data, Git, or this document. Confirm the instance role can read only those exact parameters.
6. As root, run `/opt/helpdesk/deploy/aws/scripts/prepare-host.sh /etc/helpdesk/releases/<release>.env`. With no argument, the script validates the existing active release. It verifies the EBS volume is encrypted using read-only `ec2:DescribeVolumes`. If it fails, stop and fix the named precondition; do not remove or recreate the DB directory.
7. Run `sudo /opt/helpdesk/deploy/aws/scripts/deploy.sh /etc/helpdesk/releases/<release>.env` from an SSM shell. Later deployments require a healthy running release and a successful S3 backup before replacing app and frontend containers. First deployment can initialize PostgreSQL in an empty EBS data directory. A retry can reuse the healthy PostgreSQL container when its digest matches.
8. Open the private demo through an SSM local port-forward (replace the instance ID):

   ```sh
   aws ssm start-session --region ap-southeast-2 \
     --target i-REPLACE_WITH_INSTANCE_ID \
     --document-name AWS-StartPortForwardingSession \
     --parameters '{"portNumber":["8080"],"localPortNumber":["8080"]}'
   ```

   Then browse to `http://127.0.0.1:8080`. There is no public application endpoint.

## Health, backup, restart, rollback

Run the scripts with `sudo` so the state, Docker, and ECR auth directories have consistent ownership. To verify the active release from an SSM root shell, load the same configuration and run the shared checks.

```sh
source /opt/helpdesk/deploy/aws/scripts/common.sh
load_config
verify_data_mount
load_images "$ACTIVE_IMAGES_FILE"
load_secrets
compose ps
wait_for_services
ticket_fingerprint
```

Check for a pending release before trusting the active manifest. Use `compose logs --since=10m app frontend postgres` to inspect operational errors without enabling prompt or request-body logging. Verify the loopback route, login and role restrictions. Use a dummy ticket for the write check.

Run `backup-postgres.sh` before planned schema changes. For restore rehearsal, use `restore-test-postgres.sh s3://BUCKET/PREFIX/OBJECT.dump`; it creates a new DB and leaves it intact. Do not use `pg_restore --clean`, `DROP DATABASE`, or restore over `helpdesk` in the normal rehearsal. During backup/restore count comparison, pause test writes so the counts are comparable.

Changing the DB password SecureString alone does not rotate the password in an initialized PostgreSQL data directory. Back up first, change the PostgreSQL role password through a protected session, update the SecureString, then redeploy so the app receives the matching value. Do not print either value during rotation.

Run `verify-persistence.sh` to validate container restart recovery. For the EC2 reboot test, restart the instance from the EC2 console, reconnect with SSM, and run the same script. Confirm the data filesystem mounted before Docker starts. If it did not mount, stop Compose; do not let PostgreSQL initialize an empty directory on the root disk.

`deploy.sh` rejects a PostgreSQL digest change; PostgreSQL major/minor image upgrades require a separately planned database migration. `rollback.sh` restores the previous app/frontend image manifest while keeping the same pinned PostgreSQL image. Flyway schema changes and simultaneous configuration or secret changes need their own recovery plan. If a DB schema migration is incompatible, keep the current DB and restore the pre-deploy backup into a separate recovery volume/instance first; never overwrite the sole copy.

### Release failure and manual recovery

- Release operations require `flock` and take an exclusive lock next to the active manifest. Avoid concurrent manual Docker commands, service stops, or restarts during a release.
- Login, pull, platform validation, and backup failures return nonzero before the app and frontend containers change. If starting the candidate fails, the script runs the original app and frontend images from the local cache. It verifies all three containers and the frontend route before declaring recovery. The failed command still returns nonzero.
- During replacement, `active-images.env.pending/` records the candidate and any original and previous manifests. It remains after failed recovery or an interrupted switch. The active file, if present, records the last verified release. Use the pending directory as a signal to check the live state before trusting that file. A recovery failure triggers a best-effort stop of app and frontend.
- For manual recovery, hold the same lock and inspect the manifests in the pending directory, the EBS mount, and the actual container images and health. Use the original manifest when compatible with the current schema. Start the pinned app and frontend with `compose up -d --no-deps --no-build --pull never app frontend` after loading config, secrets and that manifest via `common.sh`. If an image is missing locally, authenticate to ECR before pulling it. Verify container health and image identity with `wait_for_services`, then check login and a dummy ticket. Reconcile active and previous files with the verified release and the saved copies. Rename the pending directory to a retained recovery record only after these checks pass. If recovery remains blocked, keep the record and inspect the logs and DB schema with the app and frontend stopped. First-deployment recovery may leave an initialized PostgreSQL container. Preserve the data and match the container's digest on retry.

### AWS rollout verification for the updated scripts

1. In an approved SSM session on a disposable AWS staging stack, copy the updated scripts including `release.sh`. Verify the current app and frontend image references, health, PostgreSQL container ID, and the output of `ticket_fingerprint` from `common.sh`. Use a dummy ticket and pause concurrent writes for fingerprint comparison.
2. Deploy a known-good second app and frontend release with the same PostgreSQL digest. Expect exit 0. Check the active and previous manifests against each container's `.Config.Image` and the pinned image's local ID. Run `rollback.sh` and repeat the checks with the two app and frontend releases exchanged. PostgreSQL's container ID and ticket fingerprint should match the starting values.
3. On staging, try a nonexistent app digest to exercise ECR pull failure and a purpose-built unhealthy app image to exercise activation failure. Expect a nonzero exit in both cases. Verify the original manifest and healthy running containers after recovery. To exercise a failed manual rollback, point the staging previous manifest at the unhealthy app version while retaining a copy of the known-good manifest.
4. Rehearse unrecoverable activation on staging with a controlled failure of both app image versions. Confirm the pending directory remains, app and frontend are stopped or flagged for inspection, and PostgreSQL persists. Follow manual recovery above. A distinct staging project and empty data directory are required to exercise the first-deployment case.

For planned downtime, run `stop-services.sh`, then stop the EC2 instance in the console. Do not terminate the instance or delete the dedicated EBS volume when data is needed. Run `cost-check.sh` and the Billing console review to find leftover public IPs, EBS volumes/snapshots, NAT gateways, endpoints, ECR images, S3 backups, or log groups.

## Local-only verification before AWS

From repository root, validate syntax and Compose with temporary dummy values (never real credentials):

```sh
for script in deploy/aws/scripts/*.sh; do bash -n "$script"; done
docker compose --env-file /tmp/helpdesk-aws-compose-test.env -f compose.aws.yaml config --quiet
```

Use `docker buildx build --platform linux/arm64 --load -f Dockerfile ...` and the equivalent frontend command to verify local ARM64 builds where the local Docker engine supports Buildx. Use a dedicated test Compose project/database for backup and restore rehearsal. Do not run `down -v` against the local development project.

The existing Actions workflow builds ARM64 images but does not publish them. ECR publishing, immutable tag policy tests, manual-approval deployment, and an AWS-targeted workflow remain a later change. The workflow currently verifies the ARM64 build. ECR publishing and deployment automation require separate verification.

### Release failure injection

Run `python3 deploy/aws/tests/test-release.py` from the repository root with a local ARM64 Docker engine and cached `postgres:16-alpine` and `postgres:17-alpine` images. The suite uses disposable Compose containers, real PostgreSQL data, and stubbed AWS calls and image pulls. It verifies the manifests against actual container images and health. The current failure paths were tested locally. AWS staging verification of these changes remains separate from the previously verified EC2 deployment and data recovery.

On 2026-11-24 KST, all 11 scenario groups passed on local ARM64 Docker. After failed recovery, the original PostgreSQL container ID and sentinel row still matched their values before the release changes.

### 2026-11-19 implementation verification

- `bash -n deploy/aws/scripts/*.sh`, JSON policy parsing, `docker compose ... config --quiet`, and `git diff --check`: passed.
- `docker buildx build --platform linux/arm64 --load` for the backend and frontend Dockerfiles: both images reported `arm64/linux`; no image was pushed.
- Digest-pinned Compose brought up PostgreSQL, the app, and the frontend with all three health checks healthy. A dummy ticket was dumped, restored into a separately named DB, and verified with the idempotency constraint present. The app and DB were restarted; ticket, idempotency, PENDING counts, and ticket/key fingerprint remained unchanged.
- Local release simulation with test-only Docker/AWS shims: a new app digest was activated, explicit rollback restored the prior digest, and a nonexistent digest triggered health failure followed by recovery to the prior app. The backup upload went to a local stub, not AWS S3; ECR authentication and remote image pulling were stubbed.
- Existing Java suite: 133 test cases, 121 passed and 12 skipped on the full run; the 9 PostgreSQL-only tests were then run against a disposable `helpdesk_it_test` database in a tmpfs-only container and passed 9/9. The 3 Live RAG tests remain skipped.
- Existing Python evaluator tests: 33 passed. Existing browser E2E: 6/6 passed against its deterministic AI mock (`liveLlmCalls=0`).

These checks use temporary local containers and dummy credentials. They do not verify actual EC2, EBS attachment/mount, SSM access, IAM policy evaluation, ECR pulls, S3 upload/restore, reboot recovery, billing, or Live AI/RAG costs. No AWS resource was created.

## Indicative cost estimate

The previous cost estimate is no longer applicable. Before launch, recalculate Sydney (`ap-southeast-2`) costs for the existing `t4g.small`, root and data EBS, public IPv4, ECR, S3, transfer, and taxes using [AWS Pricing Calculator](https://calculator.aws/). Keep the existing monthly $5 budget alerts and the overall $10 short-test spending target. Budget alerts do not automatically stop spending.

| Usage | EC2 | Public IPv4 | 40 GiB gp3 | Approximate total |
| --- | ---: | ---: | ---: | ---: |
| 8-hour test day | $0.33 | $0.04 | $0.04 | **$0.41** |
| 7 days continuously | $6.99 | $0.84 | $0.85 | **$8.68** |
| 730 hours (about 30 days) | $30.37 | $3.65 | $3.65 | **$37.67/month** |

After EC2 is stopped, EC2 compute and auto-assigned public IPv4 stop billing, but the 40 GiB EBS remains about `$3.65/month`; snapshots, ECR storage, and S3 backup storage remain additional. CPU surplus credit charges are avoided by `standard` credit mode, at the cost of throttling after credits are exhausted. SSM Session Manager has no additional EC2 session fee. NAT Gateway, interface endpoints, load balancers, customer-managed KMS keys, CloudWatch log ingestion, internet transfer, and external AI API calls are excluded. Budget alerts are notifications, not spend caps.
