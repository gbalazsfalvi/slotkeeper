# Native verified build supplies the locked executable jar; the runtime never downloads dependencies.
FROM eclipse-temurin:21-jre-noble@sha256:0d57a8e90c2bb844c5a8e1b2b71e11eff06dad7edcfd803cd21ed4ae203b2515
WORKDIR /opt/slotkeeper
COPY --chown=10001:10001 application/build/libs/application-0.1.0.jar slotkeeper.jar
COPY --chown=10001:10001 LICENSE NOTICE /opt/slotkeeper/
USER 10001:10001
EXPOSE 4190
ENTRYPOINT ["java", "-jar", "/opt/slotkeeper/slotkeeper.jar"]
