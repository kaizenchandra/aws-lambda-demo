locals {
  functions = {
    order-api          = { handler = "ordering.adapter.in.OrderHandler", mode = "order-api", table = "ordering", topic = "accepted", concurrency = 10, timeout = 30 }
    fulfillment-api    = { handler = "fulfillment.adapter.in.FulfillmentQueryHandler", mode = "fulfillment-api", table = "fulfillment", topic = "completed", concurrency = 5, timeout = 30 }
    fulfillment-worker = { handler = "fulfillment.adapter.in.FulfillmentHandler", mode = "fulfillment-worker", table = "fulfillment", topic = "completed", concurrency = var.worker_concurrency, timeout = 30 }
    order-relay        = { handler = "platform.outbox.RelayHandler", mode = "relay", table = "ordering", topic = "accepted", concurrency = 1, timeout = 60 }
    fulfillment-relay  = { handler = "platform.outbox.RelayHandler", mode = "relay", table = "fulfillment", topic = "completed", concurrency = 1, timeout = 60 }
  }
}
resource "aws_cloudwatch_log_group" "function" {
  for_each          = local.functions
  name              = "/aws/lambda/${local.prefix}-${each.key}"
  retention_in_days = var.local_mode ? 7 : 30
}
resource "aws_iam_role" "function" {
  for_each           = local.functions
  name               = "${local.prefix}-${each.key}"
  assume_role_policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { Service = "lambda.amazonaws.com" }, Action = "sts:AssumeRole" }] })
}
resource "aws_iam_role_policy" "function" {
  for_each = local.functions
  role     = aws_iam_role.function[each.key].id
  policy = jsonencode({ Version = "2012-10-17", Statement = concat(
    [{ Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"], Resource = "${aws_cloudwatch_log_group.function[each.key].arn}:*" }],
    [{ Effect = "Allow", Action = each.value.mode == "relay" ? ["dynamodb:Query", "dynamodb:UpdateItem"] : each.key == "fulfillment-api" ? ["dynamodb:GetItem"] : each.key == "order-api" ? ["dynamodb:GetItem", "dynamodb:PutItem"] : ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem"], Resource = each.value.mode == "relay" ? [aws_dynamodb_table.context[each.value.table].arn, "${aws_dynamodb_table.context[each.value.table].arn}/index/pending"] : [aws_dynamodb_table.context[each.value.table].arn] }],
    each.key == "fulfillment-worker" ? [
      { Effect = "Allow", Action = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"], Resource = aws_sqs_queue.work.arn },
      { Effect = "Allow", Action = ["s3:PutObject", "s3:GetObject"], Resource = "${aws_s3_bucket.receipts.arn}/receipts/*" }
    ] : [],
    each.value.mode == "relay" ? [
      { Effect = "Allow", Action = ["sns:Publish"], Resource = aws_sns_topic.events[each.value.topic].arn },
      { Effect = "Allow", Action = ["kms:Decrypt", "kms:GenerateDataKey*"], Resource = aws_kms_key.events.arn }
    ] : [],
    var.local_mode ? [] : [{ Effect = "Allow", Action = ["xray:PutTraceSegments", "xray:PutTelemetryRecords"], Resource = "*" }]
  ) })
}
resource "aws_lambda_function" "function" {
  for_each                       = local.functions
  function_name                  = "${local.prefix}-${each.key}"
  role                           = aws_iam_role.function[each.key].arn
  runtime                        = "java21"
  architectures                  = ["arm64"]
  handler                        = "com.synechisveltiosi.commerce.${each.value.handler}::handleRequest"
  filename                       = var.artifact
  source_code_hash               = filebase64sha256(var.artifact)
  memory_size                    = 1024
  timeout                        = each.value.timeout
  reserved_concurrent_executions = each.value.concurrency
  publish                        = true
  tracing_config { mode = var.local_mode ? "PassThrough" : "Active" }
  environment {
    variables = merge({ SERVICE_MODE = each.value.mode, APP_ENV = var.local_mode ? "local" : var.environment, TABLE_NAME = aws_dynamodb_table.context[each.value.table].name, TOPIC_ARN = aws_sns_topic.events[each.value.topic].arn, RECEIPT_BUCKET = aws_s3_bucket.receipts.id }, var.local_mode ? { AWS_ENDPOINT_URL = var.lambda_endpoint } : {})
  }
  depends_on = [aws_iam_role_policy.function, aws_cloudwatch_log_group.function]
  lifecycle {
    precondition {
      condition     = var.local_mode == (var.environment == "local")
      error_message = "Local endpoint/auth bypass is allowed only in the local environment."
    }
  }
}
resource "aws_lambda_alias" "live" {
  for_each         = local.functions
  name             = "live"
  function_name    = aws_lambda_function.function[each.key].function_name
  function_version = aws_lambda_function.function[each.key].version
}
resource "aws_lambda_event_source_mapping" "fulfillment" {
  event_source_arn                   = aws_sqs_queue.work.arn
  function_name                      = aws_lambda_alias.live["fulfillment-worker"].arn
  batch_size                         = 5
  maximum_batching_window_in_seconds = 5
  function_response_types            = ["ReportBatchItemFailures"]
  scaling_config { maximum_concurrency = var.worker_concurrency }
}
resource "aws_cloudwatch_event_rule" "relay" {
  name                = "${local.prefix}-outbox"
  schedule_expression = "rate(1 minute)"
}
resource "aws_sqs_queue_policy" "schedule_dead" {
  queue_url = aws_sqs_queue.dead["schedule"].url
  policy    = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { Service = "events.amazonaws.com" }, Action = "sqs:SendMessage", Resource = aws_sqs_queue.dead["schedule"].arn, Condition = { ArnEquals = { "aws:SourceArn" = aws_cloudwatch_event_rule.relay.arn } } }] })
}
resource "aws_cloudwatch_event_target" "relay" {
  for_each  = toset(["order-relay", "fulfillment-relay"])
  rule      = aws_cloudwatch_event_rule.relay.name
  target_id = each.key
  arn       = aws_lambda_alias.live[each.key].arn
  retry_policy {
    maximum_event_age_in_seconds = 3600
    maximum_retry_attempts       = 2
  }
  dead_letter_config { arn = aws_sqs_queue.dead["schedule"].arn }
  depends_on = [aws_sqs_queue_policy.schedule_dead]
}
resource "aws_lambda_permission" "schedule" {
  for_each      = toset(["order-relay", "fulfillment-relay"])
  statement_id  = "AllowSchedule"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.function[each.key].function_name
  qualifier     = aws_lambda_alias.live[each.key].name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.relay.arn
}

# The durable outbox and next scheduled run own relay recovery; avoid another
# layer of Lambda asynchronous retries on top of EventBridge and SDK retries.
resource "aws_lambda_function_event_invoke_config" "relay" {
  for_each                     = toset(["order-relay", "fulfillment-relay"])
  function_name                = aws_lambda_function.function[each.key].function_name
  qualifier                    = aws_lambda_alias.live[each.key].name
  maximum_retry_attempts       = 0
  maximum_event_age_in_seconds = 60
}
