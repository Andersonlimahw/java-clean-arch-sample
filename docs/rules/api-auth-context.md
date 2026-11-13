# API authentication context

- **Keep caller identity out of request input**: Hide `CurrentUser` controller parameters from OpenAPI because the bearer token is the source of caller identity. Verify with `mvn -Dtest=SecurityWebTest test`.
