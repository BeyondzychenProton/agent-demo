FROM docker.m.daocloud.io/library/node:24-alpine@sha256:116b800c3754b440ee0aa3ed2f20f5bf3d99a554bfd9adeea09ea8017f6e85f5 AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci --registry=https://registry.npmmirror.com --no-audit --fund=false
COPY . .
RUN npm run build

FROM docker.m.daocloud.io/library/nginx:stable-alpine@sha256:583b7f2fce1cdb8da5ad5c39ebd3c33b98b87d493190e548bdee1fa8c619c311
ENV AGENT_SCOPE_BACKEND=http://haizhuo-ai-be:8087
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
