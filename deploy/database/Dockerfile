# Keep the accepted PostgreSQL engine and data format; replace the vulnerable
# Go-based privilege helper with Alpine's small native equivalent.
FROM public.ecr.aws/docker/library/postgres:17.11-alpine@sha256:3540c0362c809df084ffac0ae69fe794515f9de92692f02178bcdce0ef0302ac
RUN apk add --no-cache su-exec=0.3-r0 zlib=1.4-r0 \
    && rm /usr/local/bin/gosu \
    && sed -i 's/exec gosu /exec su-exec /g' /usr/local/bin/docker-entrypoint.sh \
    && ! grep -q 'exec gosu ' /usr/local/bin/docker-entrypoint.sh \
    && su-exec postgres id
