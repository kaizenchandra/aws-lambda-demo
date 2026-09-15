resource "aws_api_gateway_rest_api" "commerce" {
  tags = { Project = "commerce", Environment = var.environment, ManagedBy = "terraform" }
  name = local.prefix
  endpoint_configuration { types = ["REGIONAL"] }
}
resource "aws_api_gateway_resource" "orders" {
  rest_api_id = aws_api_gateway_rest_api.commerce.id
  parent_id   = aws_api_gateway_rest_api.commerce.root_resource_id
  path_part   = "orders"
}
resource "aws_api_gateway_resource" "order" {
  rest_api_id = aws_api_gateway_rest_api.commerce.id
  parent_id   = aws_api_gateway_resource.orders.id
  path_part   = "{id}"
}
resource "aws_api_gateway_resource" "fulfillments" {
  rest_api_id = aws_api_gateway_rest_api.commerce.id
  parent_id   = aws_api_gateway_rest_api.commerce.root_resource_id
  path_part   = "fulfillments"
}
resource "aws_api_gateway_resource" "fulfillment" {
  rest_api_id = aws_api_gateway_rest_api.commerce.id
  parent_id   = aws_api_gateway_resource.fulfillments.id
  path_part   = "{id}"
}
locals {
  routes = {
    create      = { resource = aws_api_gateway_resource.orders.id, method = "POST", function = "order-api", path = "orders" }
    get         = { resource = aws_api_gateway_resource.order.id, method = "GET", function = "order-api", path = "orders/*" }
    fulfillment = { resource = aws_api_gateway_resource.fulfillment.id, method = "GET", function = "fulfillment-api", path = "fulfillments/*" }
  }
}
resource "aws_api_gateway_method" "route" {
  for_each      = local.routes
  rest_api_id   = aws_api_gateway_rest_api.commerce.id
  resource_id   = each.value.resource
  http_method   = each.value.method
  authorization = var.local_mode ? "NONE" : "AWS_IAM"
}
resource "aws_api_gateway_integration" "route" {
  for_each                = local.routes
  rest_api_id             = aws_api_gateway_rest_api.commerce.id
  resource_id             = each.value.resource
  http_method             = aws_api_gateway_method.route[each.key].http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = aws_lambda_alias.live[each.value.function].invoke_arn
  timeout_milliseconds    = 29000
}
resource "aws_lambda_permission" "api" {
  for_each      = local.routes
  statement_id  = "AllowApi-${each.key}"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.function[each.value.function].function_name
  qualifier     = aws_lambda_alias.live[each.value.function].name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.commerce.execution_arn}/*/${each.value.method}/${each.value.path}"
}
resource "aws_api_gateway_deployment" "commerce" {
  rest_api_id = aws_api_gateway_rest_api.commerce.id
  triggers    = { redeployment = sha1(jsonencode([aws_api_gateway_method.route, aws_api_gateway_integration.route])) }
  lifecycle { create_before_destroy = true }
}
resource "aws_cloudwatch_log_group" "api" {
  name              = "/aws/apigateway/${local.prefix}"
  retention_in_days = 30
}
resource "aws_iam_role" "api_logs" {
  count              = var.local_mode ? 0 : 1
  name               = "${local.prefix}-api-logs"
  assume_role_policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { Service = "apigateway.amazonaws.com" }, Action = "sts:AssumeRole" }] })
}
resource "aws_iam_role_policy" "api_logs" {
  count  = var.local_mode ? 0 : 1
  role   = aws_iam_role.api_logs[0].id
  policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Action = ["logs:CreateLogStream", "logs:DescribeLogStreams", "logs:PutLogEvents"], Resource = "${aws_cloudwatch_log_group.api.arn}:*" }, { Effect = "Allow", Action = "logs:DescribeLogGroups", Resource = "*" }] })
}
resource "aws_api_gateway_account" "logging" {
  count               = var.local_mode ? 0 : 1
  cloudwatch_role_arn = aws_iam_role.api_logs[0].arn
  depends_on          = [aws_iam_role_policy.api_logs]
}
resource "aws_api_gateway_stage" "commerce" {
  tags                 = { Project = "commerce", Environment = var.environment, ManagedBy = "terraform" }
  rest_api_id          = aws_api_gateway_rest_api.commerce.id
  deployment_id        = aws_api_gateway_deployment.commerce.id
  stage_name           = var.environment
  xray_tracing_enabled = !var.local_mode
  dynamic "access_log_settings" {
    for_each = var.local_mode ? [] : [1]
    content {
      destination_arn = aws_cloudwatch_log_group.api.arn
      format          = jsonencode({ requestId = "$context.requestId", status = "$context.status", latency = "$context.responseLatency", resourcePath = "$context.resourcePath", httpMethod = "$context.httpMethod" })
    }
  }
  depends_on = [aws_api_gateway_account.logging]
}
resource "aws_api_gateway_method_settings" "all" {
  rest_api_id = aws_api_gateway_rest_api.commerce.id
  stage_name  = aws_api_gateway_stage.commerce.stage_name
  method_path = "*/*"
  settings {
    metrics_enabled        = true
    data_trace_enabled     = false
    throttling_burst_limit = 50
    throttling_rate_limit  = 25
  }
}
output "api_url" { value = var.local_mode ? "${var.local_endpoint}/restapis/${aws_api_gateway_rest_api.commerce.id}/${var.environment}/_user_request_" : aws_api_gateway_stage.commerce.invoke_url }
output "ordering_table" { value = aws_dynamodb_table.context["ordering"].name }
output "fulfillment_table" { value = aws_dynamodb_table.context["fulfillment"].name }
output "receipts_bucket" { value = aws_s3_bucket.receipts.id }
output "work_queue_url" { value = aws_sqs_queue.work.url }
output "completion_queue_url" { value = aws_sqs_queue.completed.url }
output "processing_dlq_url" { value = aws_sqs_queue.dead["processing"].url }
output "accepted_topic_arn" { value = aws_sns_topic.events["accepted"].arn }
