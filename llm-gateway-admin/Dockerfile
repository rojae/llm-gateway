FROM node:22-alpine@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402 AS build
WORKDIR /app
COPY llm-gateway-admin/package.json llm-gateway-admin/package-lock.json ./
RUN npm ci
COPY llm-gateway-admin/app ./app
COPY llm-gateway-admin/next.config.ts llm-gateway-admin/next-env.d.ts llm-gateway-admin/tsconfig.json ./
ARG GATEWAY_API_URL=http://llm-gateway.llm-gateway.svc.cluster.local:8080
ENV GATEWAY_API_URL=$GATEWAY_API_URL
RUN npm run build && npm prune --omit=dev

FROM node:22-alpine@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402
WORKDIR /app
ENV NODE_ENV=production
ENV PORT=3000
ARG GATEWAY_API_URL=http://llm-gateway.llm-gateway.svc.cluster.local:8080
ENV GATEWAY_API_URL=$GATEWAY_API_URL
COPY --from=build --chown=node:node /app/package.json /app/package-lock.json ./
COPY --from=build --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/.next ./.next
COPY --from=build --chown=node:node /app/next.config.ts ./next.config.ts
COPY --from=build --chown=node:node /app/app ./app
USER node
EXPOSE 3000
CMD ["npm", "run", "start"]
