# The upstream collector image is distroless, so Docker cannot probe its health port.
# busybox:1.36.1 is dynamically linked and cannot exec without glibc. The uclibc
# tag is static and is copied only to run the healthcheck. The collector binary stays 0.114.0.
FROM busybox:1.36.1-uclibc AS tools
FROM otel/opentelemetry-collector:0.114.0
COPY --from=tools /bin/busybox /bin/busybox
