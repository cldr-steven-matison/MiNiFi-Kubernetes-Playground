FROM container.repo.cloudera.com/cloudera/nifi-minifi-java:latest
USER root

# MINIFI_HOME verified via: docker run --rm <image> find /opt -iname "*minifi*"
# This build is Apache MiNiFi Java 1.23.04-b15 (NiFi 1.x runtime).
ENV MINIFI_HOME=/opt/minifi/minifi-1.23.04-b15

# Deploy configuration
COPY config-java.yml ${MINIFI_HOME}/conf/config.yml

# Ensure output directory exists
RUN mkdir -p /tmp/minifi-test-output && chmod 777 /tmp/minifi-test-output

EXPOSE 8080

CMD ["/opt/minifi/minifi-1.23.04-b15/bin/minifi.sh", "run"]
