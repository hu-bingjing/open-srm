FROM public.ecr.aws/docker/library/node:26.13.1-alpine@sha256:6bd9ab43c0ec0190ca7b2d193906cb8b7edf6e7f4afa43ddeccd6452710bffb5 AS build
WORKDIR /workspace
COPY package.json package-lock.json .
RUN npm ci --ignore-scripts --no-fund
COPY index.html tsconfig.json vite.config.ts .
COPY src src
RUN npm run build
FROM public.ecr.aws/nginx/nginx-unprivileged:1.30-alpine@sha256:c3fc8fa482be50ef709c6438eb1bc98d50dbfe5a9b48be90711b8814f4dfd701
USER root
RUN apk add --no-cache zlib=1.4-r0
COPY --from=build /workspace/dist /usr/share/nginx/html
USER 101:101
EXPOSE 8080 8443
ENTRYPOINT ["nginx", "-g", "daemon off;"]
