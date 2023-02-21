variable "account_id" {
  description = "BFD AWS account ID"
  type        = string
}

variable "etl_bucket_id" {
  description = "The ID of the ETL/pipeline S3 Bucket"
  type        = string
}

variable "aws_kms_key_arn" {
  description = "The fully qualified KMS key ARN"
  type        = string
}