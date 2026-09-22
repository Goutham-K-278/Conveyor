#!/bin/sh
# Maven Wrapper Script — downloads Maven automatically if not installed locally
# Usage: ./mvnw <maven-goals>  (e.g. ./mvnw test, ./mvnw package)
exec java -jar .mvn/wrapper/maven-wrapper.jar "$@"
