# ==============================================================================
# TRAVELMATE DOCKERFILE — MULTI-STAGE PRODUCTION BUILD
# ==============================================================================
# Builds the WAR package with Maven and runs it on Apache Tomcat 10.1 (JDK 17).
# Compatible with Railway, Render, Fly.io, AWS App Runner, GCP Cloud Run & VPS.
# ==============================================================================

# Stage 1: Build War Archive
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /app

# Copy Maven POM and source files
COPY pom.xml .
COPY src ./src

# Package WAR file
RUN mvn clean package -DskipTests

# Stage 2: Runtime Container with Tomcat 10.1 (Jakarta EE 9/10 / Servlet 5.0+)
FROM tomcat:10.1-jdk17-temurin
WORKDIR /usr/local/tomcat

# Remove default sample web applications
RUN rm -rf webapps/ROOT webapps/ROOT.war webapps/docs webapps/examples

# Deploy application as ROOT (so it serves at /) AND as /travelmate (for compatibility)
COPY --from=builder /app/target/travelmate.war webapps/ROOT.war
COPY --from=builder /app/target/travelmate.war webapps/travelmate.war

# Create data directory for embedded H2 storage if used
RUN mkdir -p /usr/local/tomcat/data && chmod -R 777 /usr/local/tomcat/data

# Default port
ENV PORT=8080
EXPOSE 8080

# Configure Tomcat HTTP port dynamically based on $PORT (required by Render/Railway/Cloud Run)
CMD ["sh", "-c", "sed -i 's/port=\"8080\"/port=\"'\"${PORT:-8080}\"'\"/g' conf/server.xml && catalina.sh run"]

