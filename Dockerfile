# 极简运行镜像：复盘计算层（S2 情绪 / S4 主线龙头 等）。
# 与 crawler 同用 eclipse-temurin:21-jre；jar 由宿主机 maven 离线构建后 COPY。
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY target/replay-backend-*.jar app.jar

ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# server.port 由 application.yml 决定（8090）
EXPOSE 8090

ENTRYPOINT ["java", "-jar", "app.jar"]
