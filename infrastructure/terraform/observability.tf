locals {
  lambda_signals = { Errors = { threshold = 1, statistic = "Sum" }, Throttles = { threshold = 1, statistic = "Sum" }, Duration = { threshold = 25000, statistic = "Maximum" }, ConcurrentExecutions = { threshold = 5, statistic = "Maximum" } }
  lambda_alarms  = { for pair in setproduct(keys(local.functions), keys(local.lambda_signals)) : "${pair[0]}-${pair[1]}" => { function = pair[0], metric = pair[1] } }
  queue_signals  = { depth = { metric = "ApproximateNumberOfMessagesVisible", threshold = 100 }, age = { metric = "ApproximateAgeOfOldestMessage", threshold = 300 } }
}
resource "aws_cloudwatch_metric_alarm" "lambda" {
  for_each            = local.lambda_alarms
  alarm_name          = "${local.prefix}-${each.key}"
  namespace           = "AWS/Lambda"
  metric_name         = each.value.metric
  dimensions          = { FunctionName = aws_lambda_function.function[each.value.function].function_name }
  statistic           = local.lambda_signals[each.value.metric].statistic
  period              = 60
  evaluation_periods  = 2
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = local.lambda_signals[each.value.metric].threshold
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_actions
}
resource "aws_cloudwatch_metric_alarm" "queue" {
  for_each            = local.queue_signals
  alarm_name          = "${local.prefix}-work-${each.key}"
  namespace           = "AWS/SQS"
  metric_name         = each.value.metric
  dimensions          = { QueueName = aws_sqs_queue.work.name }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = each.value.threshold
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_actions
}
resource "aws_cloudwatch_metric_alarm" "dlq" {
  for_each            = aws_sqs_queue.dead
  alarm_name          = "${local.prefix}-${each.key}-dead"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = each.value.name }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_actions
}
resource "aws_cloudwatch_metric_alarm" "business" {
  for_each            = { ProcessingFailures = "fulfillment-worker", PublishFailures = "relay", ApiFailures = "order-api" }
  alarm_name          = "${local.prefix}-${each.key}"
  namespace           = "Commerce"
  metric_name         = each.key
  dimensions          = { service = each.value }
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_actions
}
resource "aws_cloudwatch_metric_alarm" "outbox_age" {
  alarm_name          = "${local.prefix}-outbox-age"
  namespace           = "Commerce"
  metric_name         = "OutboxAge"
  dimensions          = { service = "relay" }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 300
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_actions
}
resource "aws_cloudwatch_dashboard" "commerce" {
  dashboard_name = local.prefix
  dashboard_body = jsonencode({ widgets = [
    { type = "metric", x = 0, y = 0, width = 12, height = 6, properties = { region = var.region, title = "Business requests and processing", period = 60, stat = "Sum", metrics = [["Commerce", "OrdersAcceptedRequests", "service", "order-api"], ["Commerce", "OrdersProcessed", "service", "fulfillment-worker"], ["Commerce", "DuplicateEvents", "service", "fulfillment-worker"], ["Commerce", "ProcessingFailures", "service", "fulfillment-worker"]] } },
    { type = "metric", x = 12, y = 0, width = 12, height = 6, properties = { region = var.region, title = "Queue backlog and age", period = 60, stat = "Maximum", metrics = [["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", aws_sqs_queue.work.name], ["AWS/SQS", "ApproximateAgeOfOldestMessage", "QueueName", aws_sqs_queue.work.name]] } },
    { type = "metric", x = 0, y = 6, width = 24, height = 6, properties = { region = var.region, title = "Invocation latency", period = 60, stat = "p95", metrics = [for key, function in aws_lambda_function.function : ["AWS/Lambda", "Duration", "FunctionName", function.function_name]] } }
  ] })
}
