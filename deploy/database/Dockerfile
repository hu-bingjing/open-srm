# Keep the accepted PostgreSQL engine and data format; replace the vulnerable
# Go-based privilege helper with Alpine's small native equivalent.
FROM public.ecr.aws/docker/library/postgres:18.7-alpine@sha256:b6972e1115a4ff8eba5d7f7d19184926d1e16d7d1136ae1e1a7ebf286da0f015
RUN apk add --no-cache su-exec=0.3-r0 zlib=1.4-r0 \
    && rm /usr/local/bin/gosu \
    && sed -i 's/exec gosu /exec su-exec /g' /usr/local/bin/docker-entrypoint.sh \
    && ! grep -q 'exec gosu ' /usr/local/bin/docker-entrypoint.sh \
    && su-exec postgres id
