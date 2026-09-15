data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
locals {
  prefix  = "commerce-${var.environment}"
  account = var.local_mode ? "000000000000" : data.aws_caller_identity.current.account_id
}
resource "aws_dynamodb_table" "context" {
  for_each                    = toset(["ordering", "fulfillment"])
  name                        = "${local.prefix}-${each.key}"
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "pk"
  deletion_protection_enabled = !var.local_mode
  attribute {
    name = "pk"
    type = "S"
  }
  attribute {
    name = "pending"
    type = "S"
  }
  attribute {
    name = "sequence"
    type = "S"
  }
  global_secondary_index {
    name            = "pending"
    hash_key        = "pending"
    range_key       = "sequence"
    projection_type = "ALL"
  }
  ttl {
    attribute_name = "expiresAt"
    enabled        = true
  }
  point_in_time_recovery { enabled = !var.local_mode }
  server_side_encryption { enabled = true }
}
resource "aws_s3_bucket" "receipts" {
  bucket        = "${local.prefix}-${local.account}-receipts"
  force_destroy = var.local_mode
}
resource "aws_s3_bucket_public_access_block" "receipts" {
  bucket                  = aws_s3_bucket.receipts.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
resource "aws_s3_bucket_versioning" "receipts" {
  bucket = aws_s3_bucket.receipts.id
  versioning_configuration { status = "Enabled" }
}
resource "aws_s3_bucket_server_side_encryption_configuration" "receipts" {
  bucket = aws_s3_bucket.receipts.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}
resource "aws_s3_bucket_policy" "receipts" {
  count  = var.local_mode ? 0 : 1
  bucket = aws_s3_bucket.receipts.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Deny", Principal = "*", Action = "s3:*", Resource = [aws_s3_bucket.receipts.arn, "${aws_s3_bucket.receipts.arn}/*"], Condition = { Bool = { "aws:SecureTransport" = "false" } } }] })
}
resource "aws_kms_key" "events" {
  description             = "Commerce SNS event encryption"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}
resource "aws_kms_alias" "events" {
  name          = "alias/${local.prefix}-events"
  target_key_id = aws_kms_key.events.key_id
}
