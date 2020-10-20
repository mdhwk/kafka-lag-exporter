FROM mozilla/sbt as build

COPY . /build/
#COPY config/config.conf /build/src/main/resources/dev-conf.conf
#COPY config/logback.xml /build/src/main/resources

RUN cd /build \
    && sbt assembly

FROM adoptopenjdk/openjdk14:alpine-slim
RUN mkdir -p /app /app/config

COPY --from=build /build/target/scala-2.12/kafka-lag-exporter.jar /app
COPY config/config.conf /app/config
COPY config/logback.xml /app/config

CMD ["java", "-jar", "/app/kafka-lag-exporter.jar","-Dconfig.file=/app/config/config.conf"]
#"-Dlogback.configurationFile=/app/config/logback.xml"
