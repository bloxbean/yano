# Minimal runtime image for the Yano node app-chain cluster scaffold.
# Build the uber-jar first: ./gradlew :app:quarkusBuild
# Then (from the repo root):
#   docker build -t yano-node:local -f scaffolds/docker-compose-cluster/Dockerfile .
FROM eclipse-temurin:25-jre

WORKDIR /app
# Quarkus uber-jar output name is "yano-node"
COPY app/build/yano-node-runner.jar /app/yano-node.jar

# Config comes from YANO_* environment variables (see docker-compose.yml).
ENTRYPOINT ["java", "-jar", "/app/yano-node.jar"]
