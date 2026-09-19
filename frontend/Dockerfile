# Build context is frontend/:
#   docker build -t sat-pass-web frontend
#
# Two stages. Node and node_modules stay in the first one; what ships is nginx and the
# static bundle Vercel serves in production - the same `npm run build`, the same output.

FROM node:22-alpine AS build
WORKDIR /src

# Same reason as in CI: the npm shipped with Node 22 crashes on vitest's peer dependency
# chain (npm/cli#8261). package.json pins npm 12 through packageManager.
RUN npm install -g npm@12

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build


FROM nginx:1.29-alpine AS runtime

# A template, not a static file: the official image substitutes ${API_UPSTREAM} at
# start-up, so the API address is a Compose setting rather than a rebuild.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist/frontend/browser /usr/share/nginx/html

ENV API_UPSTREAM=http://api:8080
EXPOSE 80
