# ---- Stage 1: build the jar ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- Stage 2: small runtime image ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/app.jar app.jar

# Free tiers have ~512 MB RAM, so cap the JVM heap
ENV JAVA_OPTS="-Xmx300m -Xss512k"
EXPOSE 8080
# Render/Railway inject $PORT; application.yml reads it
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
