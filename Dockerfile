FROM eclipse-temurin:17-jre

ARG JAR_FILE=apps/combined-web/build/libs/precustomer-combined-web-0.0.1-SNAPSHOT.jar

WORKDIR /app
COPY ${JAR_FILE} /app/app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
