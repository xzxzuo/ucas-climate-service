FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp -DskipTests package
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN mkdir -p /app/.runtime/logs /app/.runtime/platform && chown -R 10001:10001 /app
COPY --from=build /build/target/ucas-climate-service-1.0.0.jar /app/app.jar
USER 10001
EXPOSE 8087
ENTRYPOINT ["java","-jar","/app/app.jar"]
