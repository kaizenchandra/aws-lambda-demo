.PHONY: build test integration local provision e2e failures validate
build:
	./mvnw -B clean verify
test:
	./mvnw -B test
integration:
	./mvnw -B -Pintegration verify
local:
	docker compose up -d --wait
provision:
	./scripts/init-localstack.sh
e2e:
	python3 scripts/e2e.py
failures:
	python3 scripts/failure-e2e.py
validate:
	python3 scripts/check-architecture.py
	terraform -chdir=infrastructure/terraform fmt -check
	terraform -chdir=infrastructure/terraform validate
