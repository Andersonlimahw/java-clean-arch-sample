# The upstream all-in-one image is distroless, so Docker cannot probe its admin port.
# The musl tag is a static binary. The default busybox tag is dynamically linked and cannot run here.
# It is copied only to run the healthcheck. The Jaeger binary stays 1.62.0.
FROM busybox:1.36.1-musl AS tools
FROM jaegertracing/all-in-one:1.62.0
COPY --from=tools /bin/busybox /bin/busybox
