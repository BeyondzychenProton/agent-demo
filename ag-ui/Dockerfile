FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp -Dmaven.test.skip=true package && cp target/*.jar /app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app.jar ./app.jar
RUN mkdir /data && chown 10001:10001 /data
USER 10001:10001
EXPOSE 8087
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
