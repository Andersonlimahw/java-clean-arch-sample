---
title: API Authentication Context
status: active
updated: 2026-11-13
related: [../api.md, ../architecture.md, ../../AGENTS.md]
---

# API authentication context

- **Keep caller identity out of request input**: Hide `CurrentUser` controller parameters from OpenAPI because the bearer token is the source of caller identity. Verify with `mvn -Dtest=SecurityWebTest test`.
