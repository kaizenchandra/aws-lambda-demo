terraform {
  required_version = ">= 1.9, < 2.0"
  required_providers {
    aws = { source = "hashicorp/aws", version = "6.0.0" }
  }
}
provider "aws" {
  region                      = var.region
  access_key                  = var.local_mode ? "test" : null
  secret_key                  = var.local_mode ? "test" : null
  skip_credentials_validation = var.local_mode
  skip_metadata_api_check     = var.local_mode
  skip_requesting_account_id  = var.local_mode
  s3_use_path_style           = var.local_mode
  dynamic "endpoints" {
    for_each = var.local_mode ? [1] : []
    content {
      apigateway     = var.local_endpoint
      cloudwatch     = var.local_endpoint
      cloudwatchlogs = var.local_endpoint
      dynamodb       = var.local_endpoint
      events         = var.local_endpoint
      iam            = var.local_endpoint
      kms            = var.local_endpoint
      lambda         = var.local_endpoint
      s3             = var.local_endpoint
      sns            = var.local_endpoint
      sqs            = var.local_endpoint
      sts            = var.local_endpoint
    }
  }
  default_tags { tags = var.local_mode ? {} : { Project = "commerce", Environment = var.environment, ManagedBy = "terraform" } }
}
