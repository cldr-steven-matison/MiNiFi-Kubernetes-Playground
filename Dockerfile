FROM container.repo.cloudera.com/cloudera/apacheminificpp:latest
USER root

ENV MINIFI_HOME=/opt/minifi/nifi-minifi-cpp-1.26.02

# Copy the streamlined config
COPY config.yml ${MINIFI_HOME}/conf/config.yml

# Ensure output directory exists
RUN mkdir -p /tmp/minifi-test-output && chmod 777 /tmp/minifi-test-output

EXPOSE 8080

CMD ["/opt/minifi/nifi-minifi-cpp-1.26.02/bin/minifi.sh", "run"]