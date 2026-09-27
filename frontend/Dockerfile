# syntax=docker/dockerfile:1.7
#
# Build React bằng Node, phục vụ file tĩnh bằng nginx. Image cuối không có Node.
# nginx còn chuyển /api và /auth sang gateway: trình duyệt chỉ thấy một origin,
# nên không cần CORS (giống proxy của Vite khi dev).

FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
