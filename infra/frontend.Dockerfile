# Frontend for the local Docker Compose system: build with Node 22, serve dist/ with nginx.
# Build context: frontend/ (see infra/docker-compose.yml). nginx.conf is mounted by compose.

# Stage 1: Build (same Node version as CI)
FROM node:22.11.0-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .

# Only VITE_* variables reach the browser; Vite reads them from the environment.
ARG VITE_API_BASE_URL
ARG VITE_WS_URL
ARG VITE_GRAFANA_URL
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL \
    VITE_WS_URL=$VITE_WS_URL \
    VITE_GRAFANA_URL=$VITE_GRAFANA_URL
RUN npm run build

# Stage 2: Serve
FROM nginx:1.27.2-alpine
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
