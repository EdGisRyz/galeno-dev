# --- Etapa 1: COMPILA (imagen con Maven + JDK 21) ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline   # descarga dependencias y las deja cacheadas
COPY src ./src
RUN mvn clean package -DskipTests

# --- Etapa 2: EJECUTA (imagen mínima, solo JRE) ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]