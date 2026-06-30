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

# Tesseract OCR (+ Romanian & English data) for the document-OCR fallback on non-extractable PDFs.
# Matches the app defaults: myfinance.ocr.tesseract-cmd=tesseract, myfinance.ocr.tesseract-lang=ron+eng.
# (Tesseract is the free, local first tier; Claude vision is only the fallback when it's not confident.)
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        tesseract-ocr tesseract-ocr-ron tesseract-ocr-eng \
 && rm -rf /var/lib/apt/lists/*

COPY --from=build /build/target/myfinance-backend-*.jar app.jar
EXPOSE 8080

# Default: web entrypoint. Run the worker by overriding the command:
#   java -cp app.jar -Dloader.main=ro.myfinance.WorkerApplication org.springframework.boot.loader.launch.PropertiesLauncher
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
