#!/usr/bin/env bash
set -Eeuo pipefail
set +x
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

load_config
need_command aws
printf 'Read-only inventory for resources tagged Project=helpdesk-ai-agent in %s.\n' "$AWS_REGION"
aws ec2 describe-instances --region "$AWS_REGION" \
  --filters 'Name=tag:Project,Values=helpdesk-ai-agent' \
  --query 'Reservations[].Instances[].[InstanceId,State.Name,InstanceType,PublicIpAddress]' \
  --output table --no-cli-pager
aws ec2 describe-instances --region "$AWS_REGION" \
  --filters 'Name=tag:Project,Values=helpdesk-ai-agent' \
  --query 'Reservations[].Instances[].[InstanceId,BlockDeviceMappings[].[DeviceName,Ebs.DeleteOnTermination]]' \
  --output table --no-cli-pager
aws ec2 describe-volumes --region "$AWS_REGION" \
  --filters 'Name=tag:Project,Values=helpdesk-ai-agent' \
  --query 'Volumes[].[VolumeId,State,Size,Encrypted,Attachments[0].DeleteOnTermination]' \
  --output table --no-cli-pager
aws ec2 describe-addresses --region "$AWS_REGION" \
  --filters 'Name=tag:Project,Values=helpdesk-ai-agent' \
  --query 'Addresses[].[PublicIp,AllocationId,AssociationId]' --output table --no-cli-pager
aws ec2 describe-nat-gateways --region "$AWS_REGION" \
  --filter 'Name=tag:Project,Values=helpdesk-ai-agent' \
  --query 'NatGateways[].[NatGatewayId,State]' --output table --no-cli-pager
aws ec2 describe-vpc-endpoints --region "$AWS_REGION" \
  --filters 'Name=tag:Project,Values=helpdesk-ai-agent' \
  --query 'VpcEndpoints[].[VpcEndpointId,State,ServiceName]' --output table --no-cli-pager
printf 'Also inspect ECR retained image storage, S3 backup/snapshot storage, CloudWatch log retention, and any untagged resources in the AWS Billing console.\n'
