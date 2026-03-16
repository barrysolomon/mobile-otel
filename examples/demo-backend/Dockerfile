FROM node:20-alpine AS builder
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:20-alpine
WORKDIR /app
COPY --from=builder /app/node_modules ./node_modules
COPY package.json tsconfig.json ./
COPY src/ ./src/
EXPOSE 3001
CMD ["npx", "tsx", "src/index.ts"]
