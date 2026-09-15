resource "aws_sns_topic" "events" {
  for_each          = toset(["accepted", "completed"])
  name              = "${local.prefix}-${each.key}"
  kms_master_key_id = aws_kms_key.events.arn
}
resource "aws_sqs_queue" "dead" {
  for_each                  = toset(["processing", "accepted-delivery", "completed-delivery", "schedule", "completion-consumer"])
  name                      = "${local.prefix}-${each.key}-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
}
resource "aws_sqs_queue" "work" {
  name                       = "${local.prefix}-fulfillment"
  visibility_timeout_seconds = 185
  message_retention_seconds  = 345600
  receive_wait_time_seconds  = 20
  sqs_managed_sse_enabled    = true
  redrive_policy             = jsonencode({ deadLetterTargetArn = aws_sqs_queue.dead["processing"].arn, maxReceiveCount = 5 })
}
resource "aws_sqs_queue_redrive_allow_policy" "work" {
  queue_url            = aws_sqs_queue.dead["processing"].url
  redrive_allow_policy = jsonencode({ redrivePermission = "byQueue", sourceQueueArns = [aws_sqs_queue.work.arn] })
}
resource "aws_sqs_queue" "completed" {
  name                       = "${local.prefix}-completed"
  visibility_timeout_seconds = 60
  message_retention_seconds  = 1209600
  sqs_managed_sse_enabled    = true
  redrive_policy             = jsonencode({ deadLetterTargetArn = aws_sqs_queue.dead["completion-consumer"].arn, maxReceiveCount = 5 })
}
locals {
  subscriptions = {
    accepted  = { arn = aws_sqs_queue.work.arn, url = aws_sqs_queue.work.url }
    completed = { arn = aws_sqs_queue.completed.arn, url = aws_sqs_queue.completed.url }
  }
}
resource "aws_sqs_queue_policy" "subscription" {
  for_each  = local.subscriptions
  queue_url = each.value.url
  policy    = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { Service = "sns.amazonaws.com" }, Action = "sqs:SendMessage", Resource = each.value.arn, Condition = { ArnEquals = { "aws:SourceArn" = aws_sns_topic.events[each.key].arn }, StringEquals = { "aws:SourceAccount" = local.account } } }] })
}
resource "aws_sqs_queue_policy" "delivery_dead" {
  for_each  = toset(["accepted", "completed"])
  queue_url = aws_sqs_queue.dead["${each.key}-delivery"].url
  policy    = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { Service = "sns.amazonaws.com" }, Action = "sqs:SendMessage", Resource = aws_sqs_queue.dead["${each.key}-delivery"].arn, Condition = { ArnEquals = { "aws:SourceArn" = aws_sns_topic.events[each.key].arn } } }] })
}
resource "aws_sns_topic_subscription" "queue" {
  for_each             = local.subscriptions
  topic_arn            = aws_sns_topic.events[each.key].arn
  protocol             = "sqs"
  endpoint             = each.value.arn
  raw_message_delivery = true
  redrive_policy       = jsonencode({ deadLetterTargetArn = aws_sqs_queue.dead["${each.key}-delivery"].arn })
  depends_on           = [aws_sqs_queue_policy.subscription, aws_sqs_queue_policy.delivery_dead]
}
