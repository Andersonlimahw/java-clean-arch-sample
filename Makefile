COMPOSE ?= $(shell if docker compose version >/dev/null 2>&1; then echo "docker compose"; else echo "docker-compose"; fi)

.PHONY: help up down restart debug logs ps

help:
	@echo "Available commands:"
	@echo "  make up       - Start all services in background"
	@echo "  make debug    - Start all services with app in debug mode (JDWP port 5005)"
	@echo "  make down     - Stop and remove containers"
	@echo "  make restart  - Restart all containers"
	@echo "  make logs     - Follow logs of the app container"
	@echo "  make ps       - List running containers"

up:
	$(COMPOSE) up -d --build

debug:
	$(COMPOSE) -f docker-compose.yml -f docker-compose.debug.yml up -d --build

down:
	$(COMPOSE) down

restart:
	$(COMPOSE) restart

logs:
	$(COMPOSE) logs -f app

ps:
	$(COMPOSE) ps
