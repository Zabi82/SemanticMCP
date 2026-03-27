#!/bin/bash
# Downloads the Trino JDBC driver for Ontop
# Run this once before docker compose up
set -e
TRINO_VERSION=420
JAR="trino-jdbc-${TRINO_VERSION}.jar"
URL="https://repo1.maven.org/maven2/io/trino/trino-jdbc/${TRINO_VERSION}/${JAR}"

if [ -f "trino-jdbc.jar" ]; then
  echo "trino-jdbc.jar already exists, skipping download."
else
  echo "Downloading Trino JDBC ${TRINO_VERSION}..."
  curl -L -o trino-jdbc.jar "$URL"
  echo "Done: trino-jdbc.jar"
fi
