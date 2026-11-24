# Keep the accepted PostgreSQL engine and data format; replace the vulnerable
# Go-based privilege helper with Alpine's small native equivalent.
FROM public.ecr.aws/docker/library/postgres:19.0-alpine@sha256:cac45fb483caee3668946088a1c0532db89d5285c28dbfeac889b4068296e06f
RUN apk add --no-cache su-exec=0.3-r0 zlib=1.4-r0 \
    && rm /usr/local/bin/gosu \
    && sed -i 's/exec gosu /exec su-exec /g' /usr/local/bin/docker-entrypoint.sh \
    && ! grep -q 'exec gosu ' /usr/local/bin/docker-entrypoint.sh \
    && su-exec postgres id
