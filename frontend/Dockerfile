FROM public.ecr.aws/docker/library/node:24.22.0-alpine@sha256:44fce696f38ba8c987c4db89954051000bbd05ffd8d8659ba15ed4a8602e937f AS build
WORKDIR /workspace
COPY package.json package-lock.json .
RUN npm ci --ignore-scripts --no-fund
COPY index.html tsconfig.json vite.config.ts .
COPY src src
RUN npm run build
FROM public.ecr.aws/nginx/nginx-unprivileged:1.31-alpine@sha256:26277f4923331a5ecda9096c0e378de65a30a5037129a8036c3ef5d9feee833d
USER root
RUN apk add --no-cache zlib=1.4-r0
COPY --from=build /workspace/dist /usr/share/nginx/html
USER 101:101
EXPOSE 8080 8443
ENTRYPOINT ["nginx", "-g", "daemon off;"]
