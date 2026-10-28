# 生产镜像:前端构建产物由后端直接托管,单进程 8090
# 用法: docker build -t aione . && docker run -p 8090:8090 -v aione-data:/app/data aione

FROM maven:3.9-eclipse-temurin-17 AS api
WORKDIR /build
# 先只拷 pom:依赖层可复用,改代码不必重新下载依赖
COPY backend/pom.xml .
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
RUN mvn -B -q package -DskipTests

FROM node:22-alpine AS web
WORKDIR /web
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:17-jre
WORKDIR /app
# PDF 导出要嵌入中文字体,基础镜像里没有,不装的话导出件里的中文会变方块
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*
COPY --from=api /build/target/*.jar app.jar
# 后端按相对路径托管 frontend/dist,所以产物必须落在 /app/frontend/dist
COPY --from=web /web/dist frontend/dist

# data/ 里是 H2 数据库与 settings.json(含密钥),必须挂卷,否则重建容器就丢
VOLUME ["/app/data"]
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.jar"]
