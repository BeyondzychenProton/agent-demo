FROM docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /build
COPY pom.xml .
COPY docker-settings.xml /root/.m2/settings.xml
COPY src ./src
RUN mvn -B -ntp -Dmaven.test.skip=true package && cp target/*.jar /app.jar

FROM docker.m.daocloud.io/library/eclipse-temurin:21-jre@sha256:18bb4a91a3dea3f03ee31d3755af34dfb40f904fc77ab56cdc2f092e32fff9c2
WORKDIR /app
COPY --from=build /app.jar ./app.jar
USER 10001:10001
EXPOSE 8087
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
