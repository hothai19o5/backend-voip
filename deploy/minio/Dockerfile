FROM golang:1.24-bookworm AS build
ARG MINIO_RELEASE=RELEASE.2025-10-15T17-29-55Z
RUN CGO_ENABLED=0 go install github.com/minio/minio@${MINIO_RELEASE}

FROM debian:12-slim
RUN useradd --create-home --uid 10001 minio && install -d -o minio -g minio /data
COPY --from=build /go/bin/minio /usr/local/bin/minio
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
USER minio
ENTRYPOINT ["/usr/local/bin/minio"]
