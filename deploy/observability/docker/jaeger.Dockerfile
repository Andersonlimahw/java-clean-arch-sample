# The upstream all-in-one image is distroless, so Docker cannot probe its admin port.
# busybox:1.36.1 is dynamically linked and cannot exec without glibc. The uclibc
# tag is static and is copied only to run the healthcheck. The Jaeger binary stays 1.62.0.
FROM busybox:1.36.1-uclibc AS tools
FROM jaegertracing/all-in-one:1.62.0
COPY --from=tools /bin/busybox /bin/busybox
