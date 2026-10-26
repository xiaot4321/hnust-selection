FROM node:22.20.0-bookworm-slim AS build
WORKDIR /build
COPY package.json package-lock.json ./
# Reuse downloaded packages when the lockfile changes and npm ci must run again.
RUN --mount=type=cache,id=hnust-selection-npm,target=/root/.npm,sharing=locked \
    npm ci --prefer-offline --no-audit --no-fund
COPY index.html vite.config.ts tsconfig.json ./
COPY src ./src
ENV VITE_API_BASE_URL=/api
RUN npm run build

FROM nginx:stable-alpine
COPY --from=build /build/dist /usr/share/nginx/html
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY deploy/app.conf /etc/nginx/snippets/app.conf
EXPOSE 80 443
