variable "region" {
  type    = string
  default = "us-east-1"
}
variable "environment" {
  type    = string
  default = "dev"
  validation {
    condition     = contains(["local", "dev", "staging", "prod"], var.environment)
    error_message = "Use local, dev, staging or prod."
  }
}
variable "local_mode" {
  type    = bool
  default = false
}
variable "local_endpoint" {
  type    = string
  default = "http://localhost:4566"
}
variable "lambda_endpoint" {
  type    = string
  default = "http://localstack:4566"
}
variable "artifact" {
  type    = string
  default = "../../runtime/target/commerce.zip"
}
variable "alarm_actions" {
  type    = list(string)
  default = []
}
variable "worker_concurrency" {
  type    = number
  default = 5
  validation {
    condition     = var.worker_concurrency >= 2 && var.worker_concurrency <= 100
    error_message = "Worker concurrency must be 2..100."
  }
}
