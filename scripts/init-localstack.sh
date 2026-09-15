#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v terraform >/dev/null || { echo "Install Terraform >=1.9 and <2.0 before provisioning." >&2; exit 1; }
python3 - <<'PY2'
import json, urllib.request
with urllib.request.urlopen('http://localhost:4566/_localstack/health',timeout=5) as response:
    json.load(response)
PY2
./mvnw -B -DskipTests package
mkdir -p .local
terraform -chdir=infrastructure/terraform init -input=false
terraform -chdir=infrastructure/terraform apply -input=false -auto-approve -var='local_mode=true' -var='environment=local' -state=../../.local/terraform.tfstate
terraform -chdir=infrastructure/terraform output -state=../../.local/terraform.tfstate -json > .local/outputs.json
printf 'Local resources reconciled. Run python3 scripts/e2e.py\n'
