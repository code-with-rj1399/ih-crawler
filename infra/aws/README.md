# AWS deployment

InterviewHQ crawler is packaged as a Spring Boot + Playwright container and is intended to run as a single ECS Fargate service.

## AWS architecture

```
                         Internet
                            ^
                            |
                     NAT Gateway
                            |
                    Private Subnets
                            |
                    ECS Fargate task
                  interviewhq-crawler
                    /       |       \
                   /        |        \
             DynamoDB   OpenAI API   web sources
                   |
             CloudWatch Logs
```

The crawler is outbound-only: there is no public load balancer or inbound security-group rule. AWS recommends private ECS networking with NAT for workloads that need outbound internet access.

## Prerequisites

Create/provide:

- An AWS VPC.
- One or more **private subnets with NAT gateway outbound access**.
- A Secrets Manager secret containing the OpenAI API key.
- AWS CLI configured with permission to create ECR/ECS/IAM/CloudWatch resources.
- A DynamoDB table named `interviewhq-crawler-prod`.

The application no longer creates the DynamoDB table when the `prod` profile is active. Infrastructure owns the table lifecycle.

## Build and push

From repository root:

```bash
export AWS_REGION=ap-south-1
export AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
export ECR_REPO=interviewhq-crawler

aws ecr describe-repositories --repository-names "$ECR_REPO" >/dev/null 2>&1 || \
  aws ecr create-repository --repository-name "$ECR_REPO" \
    --image-scanning-configuration scanOnPush=true

aws ecr get-login-password --region "$AWS_REGION" | \
  docker login --username AWS --password-stdin "$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"

docker build -t "$ECR_REPO:prod-ready" .
docker tag "$ECR_REPO:prod-ready" "$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:prod-ready"
docker push "$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:prod-ready"
```

ECR is used so ECS can pull a private image with the standard ECS task execution role.

## Deploy the ECS stack

```bash
aws cloudformation deploy \
  --region "$AWS_REGION" \
  --stack-name interviewhq-crawler-prod \
  --template-file infra/aws/ecs-fargate.yml \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides \
    VpcId=vpc-xxxxxxxx \
    PrivateSubnetIds="subnet-aaaa,subnet-bbbb" \
    ContainerImage="$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:prod-ready" \
    OpenAiSecretArn="arn:aws:secretsmanager:$AWS_REGION:$AWS_ACCOUNT_ID:secret:interviewhq/openai" \
    DynamoDbTableName=interviewhq-crawler-prod \
    CrawlerCron="0 0 * * * *" \
    CrawlerLookbackHours=48
```

The stack creates:

- ECS Fargate cluster
- ECS service with one task
- production task definition
- least-privilege task IAM role for DynamoDB
- ECS execution role for ECR/CloudWatch/Secrets Manager
- CloudWatch log group
- outbound-only security group

ECS uses separate task and task-execution roles for application permissions versus ECS infrastructure permissions.

## Scheduling

The crawler schedule is controlled by:

```text
CRAWLER_CRON
CRAWLER_CRON_ZONE
```

Default:

```text
0 0 * * * *
```

That means every hour.

Examples:

```text
0 0 * * * *       # every hour
0 0 */2 * * *     # every 2 hours
0 0 0 * * *       # daily at midnight UTC
0 0 2 * * MON-FRI # weekdays at 02:00 UTC
```

Spring supports cron-based scheduling and timezone configuration through `@Scheduled`.

For this service we intentionally keep **one ECS task** because the crawler itself owns the schedule and uses an in-process synchronization guard. Do not increase ECS desired count until distributed job locking is added.

## Logs

Container stdout/stderr is sent to:

```text
/aws/ecs/interviewhq-crawler
```

Fargate supports the `awslogs` driver for sending container logs to CloudWatch Logs.

## Secrets

`OPENAI_API_KEY` is injected from AWS Secrets Manager rather than committed to Git or placed in the task definition as plaintext. ECS requires the task execution role to have `secretsmanager:GetSecretValue` for referenced secrets.

## Production checklist

Before going live:

- [ ] DynamoDB production table exists.
- [ ] DynamoDB table is PAY_PER_REQUEST initially.
- [ ] OpenAI key is stored in Secrets Manager.
- [ ] Private subnets have NAT outbound access.
- [ ] ECS task has no public IP.
- [ ] ECR image scanning is enabled.
- [ ] CloudWatch retention is configured.
- [ ] ECS desired count remains 1.
- [ ] `CRAWLER_CRON` is configured.
- [ ] `CRAWLER_LOOKBACK_HOURS` is configured.
- [ ] Verify `/actuator/health`.
- [ ] Run one manual ECS deployment and inspect crawler logs.
- [ ] Add hq-API ingestion before making crawler output the backend's system of record.

## Future deployment improvement

Once hq-API is ready, the crawler should stop treating DynamoDB as its long-term application datastore and instead publish normalized crawl results to hq-API. The crawler task should retain only transient crawl state/checkpoints where required.
