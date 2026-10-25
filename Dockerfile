FROM eclipse-temurin:17-jre-noble

WORKDIR /app
RUN groupadd --system --gid 10001 app && \
    useradd --system --uid 10001 --gid app --no-create-home app

COPY target/*.jar app.jar

USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
