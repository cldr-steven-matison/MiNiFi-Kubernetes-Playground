# MiNiFi Kubernetes Playground
This reposistory is used in testing minifi with [Cloudera Streaming Operators](https://cldr-steven-matison.github.io/blog/Cloudera-Streaming-Operators/).

# MiNiFi-Kubernetes-Playground

This guide provides a definitive, "Clean Slate" workflow for iterating on **Apache MiNiFi C++ (v1.26.02)** within a **Minikube** environment on **macOS**.   It was generated after working with Gemini on the Grok plan file [MiNiFi CFM MiniKube](minifi-cfm-minikube-grok.md).  The terminal history and output is archived in the [history](/history) folder.

It eliminates common caching "ghosts" by building images directly inside the Minikube Docker daemon and ensures the MiNiFi C++ agent's strict YAML requirements are met.

---

## 1. The "Nuclear" Iteration Script
Use this sequence to completely wipe the existing environment and rebuild from source. This ensures no old image layers or "Terminating" pods interfere with your test.

```bash
# --- 1. DESTRUCTIVE CLEANUP ---
# Force delete the deployment and service to clear the namespace
kubectl delete deployment minifi-test --force --grace-period=0
kubectl delete service minifi-test-service --ignore-not-found

# --- 2. ENVIRONMENT SYNC ---
# Point your terminal's Docker client to the engine INSIDE Minikube
eval $(minikube docker-env)

# --- 3. CACHE PURGE ---
# Remove the local image and wipe the build cache within Minikube
docker rmi -f minifi-test:latest || true
docker builder prune -a -f

# --- 4. AUTHENTICATION ---
# Login to the registry from within the Minikube Docker context
docker login container.repo.cloudera.com

# --- 5. NATIVE BUILD ---
# Build the image directly on the Minikube node (bypasses 'minikube image load')
docker build --no-cache --platform linux/amd64 -t minifi-test:latest .

# --- 6. DEPLOY & INITIALIZE ---
kubectl apply -f minifi-test.yaml

# --- 7. MONITOR ---
# Wait for 1/1 READY status
kubectl get pods -w
```

---

## 2. Configuration Files

### `config.yml`
**Key Requirements Included:** Explicit UUID `id` fields for all components, correct C++ class names, and mandatory `Client Name` for Kafka.

```yaml
Flow Controller:
  name: MiNiFi HTTP to Kafka

Processors:
- name: ListenHTTP
  id: 489c62c4-2d12-11f1-baac-62f0ccd85bcd
  class: ListenHTTP
  Properties:
    Listening Port: 8080

- name: PublishKafka
  id: 489c62c6-2d12-11f1-baac-62f0ccd85bcd
  class: PublishKafka
  Properties:
    Known Brokers: my-cluster-kafka-bootstrap.cld-streaming.svc:9092
    Topic Name: test-minifi
    Client Name: minifi-test-client
    Batch Size: '10'

- name: DebugLog
  id: 489c62c7-2d12-11f1-baac-62f0ccd85bcd
  class: PutFile
  Properties:
    Directory: /tmp/minifi-test-output

Connections:
- name: HttpToKafka
  id: 489c62c8-2d12-11f1-baac-62f0ccd85bcd
  source name: ListenHTTP
  destination name: PublishKafka
  source relationship name: success

- name: HttpToLog
  id: 489c62ca-2d12-11f1-baac-62f0ccd85bcd
  source name: ListenHTTP
  destination name: DebugLog
  source relationship name: success

Remote Processing Groups: []
```

### `Dockerfile`
Bakes the configuration into the specific versioned path required by the Cloudera MiNiFi C++ image.

```dockerfile
FROM container.repo.cloudera.com/cloudera/apacheminificpp:latest
USER root

# Set home directory verified via agent logs
ENV MINIFI_HOME=/opt/minifi/nifi-minifi-cpp-1.26.02

# Deploy configuration
COPY config.yml ${MINIFI_HOME}/conf/config.yml

# Create local sink directory for PutFile (DebugLog)
RUN mkdir -p /tmp/minifi-test-output && chmod 777 /tmp/minifi-test-output

EXPOSE 8080

CMD ["/opt/minifi/nifi-minifi-cpp-1.26.02/bin/minifi.sh", "run"]
```

### `minifi-test.yaml`
**Key Fix:** The `readinessProbe` path is matched to `/contentListener` to ensure Kubernetes marks the pod as `Ready`.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: minifi-test-service
spec:
  type: NodePort
  selector:
    app: minifi-test
  ports:
    - protocol: TCP
      port: 8080
      targetPort: 8080
      nodePort: 30080
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: minifi-test
spec:
  replicas: 1
  selector:
    matchLabels:
      app: minifi-test
  template:
    metadata:
      labels:
        app: minifi-test
    spec:
      serviceAccountName: minifi-controller
      containers:
      - name: minifi
        image: minifi-test:latest
        imagePullPolicy: IfNotPresent
        ports:
        - containerPort: 8080
        readinessProbe:
          httpGet:
            path: /contentListener
            port: 8080
          initialDelaySeconds: 5
          periodSeconds: 5
```

---

## 3. Verification & Testing

### Step 1: Open the Network Tunnel
On macOS, keep this terminal window open to bridge the Minikube network to your host.
```bash
minikube service minifi-test-service --url
```

### Step 2: Trigger the Flow (Curl)
Using the URL from the tunnel (e.g., `http://127.0.0.1:53314`), POST data to the agent.
```bash
curl -i -X POST http://127.0.0.1:<TUNNEL_PORT>/contentListener \
     -H "Content-Type: application/json" \
     -d '{"test_id": "integration-success", "message": "Flow is functional"}'
```

### Step 3: Verify Kafka Delivery
Run a temporary consumer pod to confirm the message landed in the Kafka topic.
```bash
kubectl run kafka-viewer -it --rm \
  --image=quay.io/strimzi/kafka:latest-kafka-3.7.0 \
  --restart=Never \
  -- bin/kafka-console-consumer.sh \
  --bootstrap-server my-cluster-kafka-bootstrap.cld-streaming.svc:9092 \
  --topic test-minifi \
  --from-beginning \
  --timeout-ms 10000
```

### Step 4: Verify Local Persistence
Check the internal pod storage to ensure the `PutFile` processor successfully mirrored the data.
```bash
kubectl exec -it deployment/minifi-test -- /bin/sh -c "cat /tmp/minifi-test-output/*"
```