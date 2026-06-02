# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -e -B dependency:go-offline
COPY src ./src
RUN mvn -q -e -B clean package -DskipTests

# ---- runtime ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/myfinance-backend-*.jar app.jar
EXPOSE 8080

# Default: web entrypoint. Run the worker by overriding the command:
#   java -cp app.jar -Dloader.main=ro.myfinance.WorkerApplication org.springframework.boot.loader.launch.PropertiesLauncher
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
