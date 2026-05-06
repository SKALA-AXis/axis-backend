FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /app

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x ./gradlew
RUN ./gradlew dependencies --no-daemon -q

COPY src src
RUN ./gradlew clean bootJar --no-daemon -q

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN addgroup --system axis && adduser --system --ingroup axis axis
COPY --from=builder /app/build/libs/axis-backend.jar app.jar
USER axis

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
