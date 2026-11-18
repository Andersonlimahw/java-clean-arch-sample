COMPOSE ?= $(shell if docker compose version >/dev/null 2>&1; then echo "docker compose"; else echo "docker-compose"; fi)
IMAGE ?= payments-platform
TAG ?= 0.1.0

.PHONY: help up up-prebuilt down restart debug logs ps clean publish-image

help:
	@echo "Available commands:"
	@echo "  make up            - Build and start all services in background"
	@echo "  make up-prebuilt   - Start all services using prebuilt PAYMENTS_IMAGE without building"
	@echo "  make debug         - Start all services with app in debug mode (JDWP port 5005) and follow logs"
	@echo "  make publish-image - Multi-arch buildx publish (IMAGE=<owner/repo> TAG=<semver>)"
	@echo "  make down          - Stop and remove containers"
	@echo "  make clean         - Stop and remove containers, volumes and orphans"
	@echo "  make restart       - Restart all containers"
	@echo "  make logs          - Follow logs of the app container"
	@echo "  make ps            - List running containers"

up:
	$(COMPOSE) up -d --build

up-prebuilt:
	$(COMPOSE) up -d --no-build

debug:
	$(COMPOSE) -f docker-compose.yml -f docker-compose.debug.yml up -d --build
	$(COMPOSE) -f docker-compose.yml -f docker-compose.debug.yml logs -f app

publish-image:
	bash scripts/docker-publish.sh --image "$(IMAGE)" --tag "$(TAG)" --push

down:
	$(COMPOSE) down

clean:
	$(COMPOSE) down -v --remove-orphans

restart:
	$(COMPOSE) restart

logs:
	$(COMPOSE) logs -f app

ps:
	$(COMPOSE) ps
