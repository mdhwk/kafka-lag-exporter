FROM maven:3.6.3-openjdk-14-slim as builder

RUN mkdir /build
WORKDIR /build
COPY . .

RUN mvn clean compile test install

FROM adoptopenjdk/openjdk14:alpine-slim
RUN mkdir -p /app /app/config

COPY --from=builder /build/target/kafka-exporter-*-fat.jar /app/kafka-lag-exporter.jar
COPY confs /app/config
COPY build/run /app/run

ENTRYPOINT ["java", "-Dconfig.file=/app/config/main.conf", "-Dlogback.configurationFile=/app/config/logback.xml", "-jar", "/app/kafka-lag-exporter.jar"]

