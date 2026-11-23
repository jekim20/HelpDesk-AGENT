# AWS ARM64 deployment operations

This guide prepares a private, single-instance demo in `ap-northeast-2`. It does not create AWS resources. Do not run any AWS command below until the resource list and estimated spend have been approved.

## Deployment files

- `compose.aws.yaml` runs only digest-pinned images. It never builds from source. PostgreSQL data is bind-mounted from the dedicated EBS mount; the app and database have no host-published ports; Nginx is bound to `127.0.0.1`.
- `deploy/aws/scripts/record-release.sh` records three immutable image references into a mode-0600 release manifest.
- `prepare-host.sh` verifies ARM64, Compose, the EBS mount and its encryption, PostgreSQL directory ownership, SSM parameters, and Compose configuration. It never formats a disk or deletes data.
- `deploy.sh` backs up the current DB to S3 before replacing a running release, stores the previous image manifest, pulls the candidate, starts services, and checks container health plus the frontend route. A failed release attempts an image-only rollback.
- `rollback.sh` activates the prior image manifest and verifies health. It never rolls back or drops database state.
- `backup-postgres.sh` writes a custom-format `pg_dump` file and uploads it to the configured S3 prefix with SSE-S3. `--local-only PATH` is available for isolated local backup testing; it refuses to overwrite a file.
- `restore-test-postgres.sh BACKUP` restores into a unique, new database and verifies ticket counts, PENDING counts, idempotency-key counts, and the unique constraint. It never drops a database. A failed restore database is intentionally left for inspection.
- `verify-persistence.sh` compares ticket counts and a ticket/key fingerprint before and after PostgreSQL, app, and frontend restarts.
- `stop-services.sh` stops Compose without deleting the EBS data. EC2, EBS, S3, ECR, and snapshots can continue to incur charges.
- `cost-check.sh` is a read-only AWS inventory for resources tagged `Project=helpdesk-ai-agent`; it cannot find untagged resources, so check the Billing console too.

All shell scripts disable tracing. They do not print parameter values. Application secrets are read at runtime from SSM Parameter Store `SecureString` through the instance role and exported only to the Compose process. Docker administrators can still see container environment values using Docker inspection; restrict host access to the operator.

## AWS resources and settings to approve

1. One EC2 `t4g.medium` running Amazon Linux 2023 ARM64 in `ap-northeast-2`, with CPU credit mode `standard` for predictable spend. The OS/root EBS volume can be deleted with the instance; PostgreSQL data is on the separate data volume below.
2. One encrypted gp3 EBS data volume (20 GiB initial proposal), attached separately and tagged `Project=helpdesk-ai-agent`. Keep it after instance termination (`DeleteOnTermination=false`). Snapshot retention is a separate cost and must be selected explicitly.
3. One security group with **no ingress rules**. Allow TCP 443 egress for SSM, ECR, S3, package updates, and later explicitly approved HTTPS AI calls. Do not expose ports 22, 5432, or 8080.
4. One instance profile with `deploy/aws/iam/instance-role-policy.template.json` narrowed to the actual account, ECR repos, parameter ARNs, backup bucket/prefix, and KMS context. It grants no EC2 create/modify/terminate or IAM permissions.
5. Two private ECR repositories (`helpdesk-app`, `helpdesk-frontend`) with tag immutability enabled. ECR stores the ARM64 image artifacts; runtime references use manifest digests. Keep at least the current and previous release.
6. One private S3 backup bucket in Seoul with Block Public Access, SSE-S3 default encryption, unique timestamped object keys, and an agreed lifecycle (initial proposal: 30 days). The host role can read/write only `helpdesk/postgres/*`; it cannot delete backups.
7. Four required SSM SecureString parameters for DB and demo account passwords. An OpenAI key parameter is optional and must not be created or loaded until Live AI/RAG use is separately approved. Use the AWS-managed `alias/aws/ssm` key to avoid creating a billable customer KMS key unless customer-managed key control is required.
8. No NAT Gateway, load balancer, RDS, static Elastic IP, or VPC interface endpoints in the initial low-cost topology. Use an automatically assigned public IPv4 in a public subnet, with inbound blocked, for outbound service access. Stop the instance when not testing; automatic public IPv4 is released when stopped.

The operator policy template is deliberately separate from the instance role. Scope `StartSession` to this instance and the `AWS-StartPortForwardingSession` document. The own-session ARN pattern in the template must be validated for the actual IAM Identity Center principal before use. Require MFA. Session Manager port forwarding is encrypted, but AWS does not log the forwarded application payload.

### EBS filesystem

Identify the attached data disk by its EBS volume serial in `lsblk -o NAME,SERIAL,FSTYPE,MOUNTPOINTS`. Never select a disk by guessed `/dev/nvme*` ordering. Formatting is destructive: only an operator who has verified the new volume ID, empty filesystem, and encryption may create a filesystem. The scripts intentionally do not run `mkfs`.

Mount the verified filesystem at `/srv/helpdesk-data`, configure its UUID in `/etc/fstab`, then create `/srv/helpdesk-data/postgres`. Set `HELPDESK_PGDATA_PATH` to that directory and `HELPDESK_EBS_MOUNT` to the mount point. `prepare-host.sh` checks that the Postgres path resolves to that mounted volume and fails closed if the disk is absent. Install `deploy/aws/systemd/docker.service.d/20-helpdesk-ebs.conf` as `/etc/systemd/system/docker.service.d/20-helpdesk-ebs.conf`, then run `systemctl daemon-reload`; this makes Docker wait for the data mount at boot so PostgreSQL cannot silently initialize on the root disk if the EBS mount is missing.

## First deployment sequence (after approval)

1. Confirm the approved account and region; create/tag the resources above. Record the EC2 instance ID, EBS volume ID, ECR repository URIs, S3 bucket, and SecureString parameter names in `/etc/helpdesk/aws-deploy.env` based on `deploy/aws/config.example.env`. The file contains names and IDs only, never values of secrets.
2. Attach the encrypted data EBS and mount it persistently. Install/enable Docker Engine, Docker Compose v2, AWS CLI v2, and the SSM Agent on AL2023 using AWS's current Amazon Linux instructions. Confirm SSM reports the node online before opening a local tunnel.
3. Build and publish the frontend and backend for `linux/arm64` to the two immutable ECR repositories, tagged with the Git commit SHA. Resolve the manifest digests from ECR. Resolve the official `postgres:17-alpine` ARM64 image digest too. Do not deploy mutable `latest` tags.
4. Copy `compose.aws.yaml` and scripts to `/opt/helpdesk`; install scripts executable. Create `/etc/helpdesk/releases/` and `/var/lib/helpdesk/` with mode 0700. Record exact digests with `record-release.sh`; for the first release, install that manifest as `/var/lib/helpdesk/active-images.env`.
5. Create the SecureString parameters from the AWS console or an approved secure workflow; do not put secret values in shell history, instance user data, Git, or this document. Confirm the instance role can read only those exact parameters.
6. As root, run `/opt/helpdesk/deploy/aws/scripts/prepare-host.sh`. It verifies the EBS volume is encrypted using read-only `ec2:DescribeVolumes`. If it fails, stop and fix the named precondition; do not remove or recreate the DB directory.
7. Run `sudo /opt/helpdesk/deploy/aws/scripts/deploy.sh /etc/helpdesk/releases/<release>.env` from an SSM shell. The initial release has no prior DB to back up; later deployments require the S3 backup to succeed before replacing a running database version.
8. Open the private demo through an SSM local port-forward (replace the instance ID):

   ```sh
   aws ssm start-session --region ap-northeast-2 \
     --target i-REPLACE_WITH_INSTANCE_ID \
     --document-name AWS-StartPortForwardingSession \
     --parameters '{"portNumber":["8080"],"localPortNumber":["8080"]}'
   ```

   Then browse to `http://127.0.0.1:8080`. There is no public application endpoint.

## Health, backup, restart, rollback

From an SSM shell, inspect services with `sudo docker compose --project-name helpdesk-agent --env-file /dev/null -f /opt/helpdesk/compose.aws.yaml ps`. Inspect `logs --since=10m app frontend postgres` only for operational errors. Do not enable prompt/request-body logging. Verify `http://127.0.0.1:8080/`, login, role restrictions, and a dummy ticket before calling a deployment healthy. Run all scripts with `sudo` so `/var/lib/helpdesk`, Docker, and the temporary ECR auth directory under `/run` have consistent ownership.

Run `backup-postgres.sh` before planned schema changes. For restore rehearsal, use `restore-test-postgres.sh s3://BUCKET/PREFIX/OBJECT.dump`; it creates a new DB and leaves it intact. Do not use `pg_restore --clean`, `DROP DATABASE`, or restore over `helpdesk` in the normal rehearsal. During backup/restore count comparison, pause test writes so the counts are comparable.

Changing the DB password SecureString alone does not rotate the password in an initialized PostgreSQL data directory. Back up first, change the PostgreSQL role password through a protected session, update the SecureString, then redeploy so the app receives the matching value. Do not print either value during rotation.

Run `verify-persistence.sh` to validate container restart recovery. For the EC2 reboot test, restart the instance from the EC2 console, reconnect with SSM, and run the same script. Confirm the data filesystem mounted before Docker starts. If it did not mount, stop Compose; do not let PostgreSQL initialize an empty directory on the root disk.

`deploy.sh` rejects a PostgreSQL digest change; PostgreSQL major/minor image upgrades require a separately planned database migration. `rollback.sh` restores the previous app/frontend image manifest while keeping the same pinned PostgreSQL image. It does not reverse Flyway migrations. If a DB schema migration is incompatible, keep the current DB and restore the pre-deploy backup into a separate recovery volume/instance first; never overwrite the sole copy.

For planned downtime, run `stop-services.sh`, then stop the EC2 instance in the console. Do not terminate the instance or delete the dedicated EBS volume when data is needed. Run `cost-check.sh` and the Billing console review to find leftover public IPs, EBS volumes/snapshots, NAT gateways, endpoints, ECR images, S3 backups, or log groups.

## Local-only verification before AWS

From repository root, validate syntax and Compose with temporary dummy values (never real credentials):

```sh
for script in deploy/aws/scripts/*.sh; do bash -n "$script"; done
docker compose --env-file /tmp/helpdesk-aws-compose-test.env -f compose.aws.yaml config --quiet
```

Use `docker buildx build --platform linux/arm64 --load -f Dockerfile ...` and the equivalent frontend command to verify local ARM64 builds where the local Docker engine supports Buildx. Use a dedicated test Compose project/database for backup and restore rehearsal. Do not run `down -v` against the local development project.

The existing Actions workflow builds ARM64 images but does not publish them. ECR publishing, immutable tag policy tests, manual-approval deployment, and an AWS-targeted workflow remain a later change. AWS behavior is unverified until these steps are performed.

### 2026-11-19 implementation verification

- `bash -n deploy/aws/scripts/*.sh`, JSON policy parsing, `docker compose ... config --quiet`, and `git diff --check`: passed.
- `docker buildx build --platform linux/arm64 --load` for the backend and frontend Dockerfiles: both images reported `arm64/linux`; no image was pushed.
- Digest-pinned Compose brought up PostgreSQL, the app, and the frontend with all three health checks healthy. A dummy ticket was dumped, restored into a separately named DB, and verified with the idempotency constraint present. The app and DB were restarted; ticket, idempotency, PENDING counts, and ticket/key fingerprint remained unchanged.
- Local release simulation with test-only Docker/AWS shims: a new app digest was activated, explicit rollback restored the prior digest, and a nonexistent digest triggered health failure followed by recovery to the prior app. The backup upload went to a local stub, not AWS S3; ECR authentication and remote image pulling were stubbed.
- Existing Java suite: 133 test cases, 121 passed and 12 skipped on the full run; the 9 PostgreSQL-only tests were then run against a disposable `helpdesk_it_test` database in a tmpfs-only container and passed 9/9. The 3 Live RAG tests remain skipped.
- Existing Python evaluator tests: 33 passed. Existing browser E2E: 6/6 passed against its deterministic AI mock (`liveLlmCalls=0`).

These checks use temporary local containers and dummy credentials. They do not verify actual EC2, EBS attachment/mount, SSM access, IAM policy evaluation, ECR pulls, S3 upload/restore, reboot recovery, billing, or Live AI/RAG costs. No AWS resource was created.

## Indicative cost estimate

Excluding taxes and variable storage/traffic, the initial estimate uses the Seoul On-Demand t4g.medium rate of about `$0.0416/hour`, public IPv4 `$0.005/hour`, a 20 GiB gp3 root disk plus 20 GiB gp3 data disk at about `$0.0912/GiB-month`. Recheck exact line items in the [AWS Pricing Calculator](https://calculator.aws/) before launch; regional prices and pricing can change. The public IPv4 rate is listed on [AWS VPC pricing](https://aws.amazon.com/vpc/pricing/); see [EC2](https://aws.amazon.com/ec2/pricing/on-demand/) and [EBS](https://aws.amazon.com/ebs/pricing/) pricing pages.

| Usage | EC2 | Public IPv4 | 40 GiB gp3 | Approximate total |
| --- | ---: | ---: | ---: | ---: |
| 8-hour test day | $0.33 | $0.04 | $0.04 | **$0.41** |
| 7 days continuously | $6.99 | $0.84 | $0.85 | **$8.68** |
| 730 hours (about 30 days) | $30.37 | $3.65 | $3.65 | **$37.67/month** |

After EC2 is stopped, EC2 compute and auto-assigned public IPv4 stop billing, but the 40 GiB EBS remains about `$3.65/month`; snapshots, ECR storage, and S3 backup storage remain additional. CPU surplus credit charges are avoided by `standard` credit mode, at the cost of throttling after credits are exhausted. SSM Session Manager has no additional EC2 session fee. NAT Gateway, interface endpoints, load balancers, customer-managed KMS keys, CloudWatch log ingestion, internet transfer, and external AI API calls are excluded. Budget alerts are notifications, not spend caps.
