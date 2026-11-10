#!/bin/sh
# Run from project root on a disposable Docker-capable development/test host.
set -eu
mkdir -p tmp/m2
docker build -t open-srm-database:0.8.0-local deploy/database
docker run --rm --network host \
  -e TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$(pwd):/workspace" -w /workspace/backend \
  public.ecr.aws/docker/library/maven:3.9.11-eclipse-temurin-21-alpine@sha256:922927df2c662cdd47ddb116443d6bec4696cfae3de1a0ddac8fcc7b87ce61ae \
  mvn -B -ntp -Dmaven.repo.local=/workspace/tmp/m2 -Pintegration clean verify
