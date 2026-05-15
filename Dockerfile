FROM eclipse-temurin:17-jre

ARG JAR_FILE=apps/combined-web/build/libs/precustomer-combined-web-0.0.1-SNAPSHOT.jar

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY ${JAR_FILE} /app/app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
