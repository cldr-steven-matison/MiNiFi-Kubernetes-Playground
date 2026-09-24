# MiNiFi-Kubernetes-Playground
This reposistory is used in testing minifi with [Cloudera Streaming Operators](https://cldr-steven-matison.github.io/blog/Cloudera-Streaming-Operators/).


This guide provides a definitive, "Clean Slate" workflow for iterating on **Apache MiNiFi C++ (v1.26.02)** and **Apache MiNiFi Java (1.23.04-b15)** within a **Minikube** environment. The C++ walkthrough was generated after working with Gemini on the Grok plan file [MiNiFi CFM MiniKube](minifi-cfm-minikube-grok.md). The terminal history and output is archived in the [history](/history) folder.

It eliminates common caching "ghosts" by building images directly inside the Minikube Docker daemon and ensures each agent's strict YAML requirements are met.

## Flavors at a glance

| | C++ (`apacheminificpp:latest`) | Java (`nifi-minifi-java:latest`) |
|---|---|---|
| Cloudera image | `container.repo.cloudera.com/cloudera/apacheminificpp:latest` | `container.repo.cloudera.com/cloudera/nifi-minifi-java:latest` |
| Runtime version | 1.26.02 | 1.23.04-b15 (NiFi 1.x-based) |
| Config file | `config.yml` / `Dockerfile` / `minifi-test.yaml` | `config-java.yml` / `Dockerfile.java` / `minifi-test-java.yaml` |
| Image size | ~15 MB | ~250 MB compressed pull |
| Memory request | 128Mi works | 512Mi minimum, 1Gi limit recommended |
| Startup | near-instant | ~30-60s JVM + MiNiFi bootstrap |
| Stock Kafka support | Yes (`PublishKafka` ships in the base image) | **No** — `nifi-minifi-java:latest`'s `minifi-standard-nar` does not bundle a Kafka NAR (field-verified 2026-07-29: no `*kafka*` jar anywhere under `lib/`). The Java example below is `ListenHTTP -> PutFile` only; Kafka publish would need a NAR drop-in, which isn't done in this playground yet. |
| NodePort | 30080 | 30081 |

<!-- Folded into the Complete Guide to Edge Flow Management (BrainShare) → guide/ch07-standalone-minifi-cpp-on-k8s.md (#31). This readme stays the runnable source; the chapter is the synthesized version. -->

---

## 1. The "Nuclear" Iteration Script — C++
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

## 2. Configuration Files — C++

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

## 3. Verification & Testing — C++

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

---

## 4. The "Nuclear" Iteration Script — Java

Same shape as the C++ script, swapping in the Java Dockerfile/image/manifest names. The image is
~250 MB (vs ~15 MB for C++) so the build and pull take noticeably longer.

```bash
# --- 1. DESTRUCTIVE CLEANUP ---
kubectl delete deployment minifi-test-java --force --grace-period=0
kubectl delete service minifi-test-java-service --ignore-not-found

# --- 2. ENVIRONMENT SYNC ---
eval $(minikube docker-env)

# --- 3. CACHE PURGE ---
docker rmi -f minifi-test-java:latest || true
docker builder prune -a -f

# --- 4. AUTHENTICATION ---
docker login container.repo.cloudera.com

# --- 5. NATIVE BUILD ---
docker build --no-cache --platform linux/amd64 -t minifi-test-java:latest -f Dockerfile.java .

# --- 6. DEPLOY & INITIALIZE ---
kubectl apply -f minifi-test-java.yaml

# --- 7. MONITOR ---
# Java's JVM + MiNiFi bootstrap takes 30-60s before the pod goes Ready - don't panic at
# early "Unhealthy" events, give it the full initialDelaySeconds window.
kubectl get pods -w
```

**Gotcha found building this (field-verified 2026-07-29):** the image referenced by early
drafts of this delta, `container.repo.cloudera.com/cloudera/minifi-java:latest`, does not exist
in the Cloudera registry. The real image is
`container.repo.cloudera.com/cloudera/nifi-minifi-java:latest`. `docker pull` on the wrong name
fails with a plain "not found" — worth a quick `docker pull` check before debugging anything
further downstream.

---

## 5. Configuration Files — Java

### `config-java.yml`
**Key differences from the C++ `config.yml`:** the Java MiNiFi config schema (`MiNiFi Config
Version: 3`) is considerably more verbose than the C++ one — it carries explicit `Core
Properties`, `FlowFile Repository`, `Content Repository`, etc. sections (all left at their
Cloudera-shipped defaults here), and each processor needs `scheduling strategy` /
`scheduling period` / `penalization period` / `yield period` / `auto-terminated relationships
list` fields that the C++ schema doesn't require. Connections are wired by `source id` /
`destination id` (UUID), not `source name` / `destination name`. Processor `class` values are
fully-qualified (`org.apache.nifi.processors.standard.ListenHTTP`), not the bare C++ short name.

There is no `PublishKafka` here — see the Kafka gap note above. This flow is `ListenHTTP ->
PutFile`, the same shape as the C++ flow's `DebugLog` branch.

```yaml
MiNiFi Config Version: 3
Flow Controller:
  name: MiNiFi HTTP to File (Java)
  comment: ''
Core Properties:
  flow controller graceful shutdown period: 10 sec
  flow service write delay interval: 500 ms
  administrative yield duration: 30 sec
  bored yield duration: 10 millis
  max concurrent threads: 1
# ... FlowFile/Content/Provenance/Component Status/Security Properties left at Cloudera defaults,
# see the full file for the exact values ...
Processors:
- name: ListenHTTP
  id: 6e21b840-2d12-11f1-baac-62f0ccd85bcd
  class: org.apache.nifi.processors.standard.ListenHTTP
  scheduling strategy: TIMER_DRIVEN
  scheduling period: 0 sec
  penalization period: 30 sec
  yield period: 1 sec
  run duration nanos: 0
  auto-terminated relationships list: []
  Properties:
    Listening Port: 8080
    Base Path: contentListener

- name: PutFile
  id: 6e21b842-2d12-11f1-baac-62f0ccd85bcd
  class: org.apache.nifi.processors.standard.PutFile
  scheduling strategy: TIMER_DRIVEN
  scheduling period: 0 sec
  penalization period: 30 sec
  yield period: 1 sec
  run duration nanos: 0
  auto-terminated relationships list:
  - success
  - failure
  Properties:
    Directory: /tmp/minifi-test-output

Connections:
- name: HttpToFile
  id: 6e21b844-2d12-11f1-baac-62f0ccd85bcd
  source id: 6e21b840-2d12-11f1-baac-62f0ccd85bcd
  source relationship names:
  - success
  destination id: 6e21b842-2d12-11f1-baac-62f0ccd85bcd
  max work queue size: 10000
  max work queue data size: 1 GB
  flowfile expiration: 0 sec
  queue prioritizer class: ''
```

Full file: [`config-java.yml`](config-java.yml).

### `Dockerfile.java`
Bakes the configuration into the specific versioned path required by the Cloudera MiNiFi Java
image. `MINIFI_HOME` was empirically confirmed (not documented anywhere) by running:
```bash
docker run --rm container.repo.cloudera.com/cloudera/nifi-minifi-java:latest \
  find /opt -maxdepth 4 -iname "*minifi*"
```

```dockerfile
FROM container.repo.cloudera.com/cloudera/nifi-minifi-java:latest
USER root

ENV MINIFI_HOME=/opt/minifi/minifi-1.23.04-b15

COPY config-java.yml ${MINIFI_HOME}/conf/config.yml

RUN mkdir -p /tmp/minifi-test-output && chmod 777 /tmp/minifi-test-output

EXPOSE 8080

CMD ["/opt/minifi/minifi-1.23.04-b15/bin/minifi.sh", "run"]
```

### `minifi-test-java.yaml`
**Key differences from the C++ `minifi-test.yaml`:**
- `resources.requests.memory: 512Mi` / `limits.memory: 1Gi` — the JVM needs real headroom.
- `initialDelaySeconds: 60` — the JVM + MiNiFi bootstrap takes 30-60s, vs 5s for C++.
- **`tcpSocket` probes, not `httpGet`.** This is the one real functional gotcha found deploying
  this (field-verified 2026-07-29): Java's `ListenHTTP` only accepts `POST` on `/contentListener`
  and returns `405` to a bare `GET`. Kubernetes' `httpGet` probe treats any non-2xx/3xx as a
  failed check, so reusing the C++ probe pattern here fails permanently and the pod never goes
  Ready (confirmed — it crash-loops on a failed liveness probe). A `tcpSocket` check against the
  Jetty listener sidesteps the method restriction entirely.
- `nodePort: 30081` to avoid conflict with the C++ deployment on 30080.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: minifi-test-java-service
spec:
  type: NodePort
  selector:
    app: minifi-test-java
  ports:
    - protocol: TCP
      port: 8080
      targetPort: 8080
      nodePort: 30081
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: minifi-test-java
spec:
  replicas: 1
  selector:
    matchLabels:
      app: minifi-test-java
  template:
    metadata:
      labels:
        app: minifi-test-java
    spec:
      containers:
      - name: minifi-java
        image: minifi-test-java:latest
        imagePullPolicy: IfNotPresent
        ports:
        - containerPort: 8080
        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "1Gi"
        readinessProbe:
          tcpSocket:
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 5
        livenessProbe:
          tcpSocket:
            port: 8080
          initialDelaySeconds: 75
          periodSeconds: 10
```

---

## 6. Verification & Testing — Java

### Step 1: Open the Network Tunnel
Same as C++:
```bash
minikube service minifi-test-java-service --url
```

### Step 2: Trigger the Flow (Curl)
```bash
curl -i -X POST http://127.0.0.1:<TUNNEL_PORT>/contentListener \
     -H "Content-Type: application/json" \
     -d '{"test_id": "integration-success", "message": "Flow is functional"}'
```

### Step 3: Verify Kafka Delivery
Not applicable — this stock Java build has no Kafka NAR (see the Flavors table above). Skip this
step for the Java flavor until a Kafka NAR is added.

### Step 4: Verify Local Persistence
```bash
kubectl exec -it deployment/minifi-test-java -- /bin/sh -c "cat /tmp/minifi-test-output/*"
```

Field-verified end-to-end 2026-07-29 on this playground's own Minikube cluster (context
`minikube`, `default` namespace — the same cluster the C++ flow's `my-cluster-kafka-bootstrap`
reference in `config.yml` targets via `cld-streaming`): pod `minifi-test-java-*` reached `1/1
Running`, a `POST /contentListener` returned `200`, and the exact JSON body landed in
`/tmp/minifi-test-output/<uuid>` inside the pod.

## 7. Level 2 — EFM-managed variant (both flavors)

Everything above (Levels 1–6) is a self-contained, non-EFM demo of stock open-source MiNiFi —
intentionally left as-is, no EFM dependency. This section adds a separate, additive **Level 2**:
an EFM-managed variant of each flavor, deployed alongside the Level 1 pods without modifying any
Level 1 file.

`minifi-test-efm-cpp.yaml` and `minifi-test-efm-java.yaml` are bare pods — no custom Docker image,
just a plain `ubuntu:22.04` base that installs prerequisites and runs EFM's own
`agent-deployer/script` at container startup, registering with EFM as agent classes `PlaygroundCpp`
and `PlaygroundJava` respectively:

```bash
kubectl apply -f minifi-test-efm-cpp.yaml
kubectl apply -f minifi-test-efm-java.yaml
```

Both target the same cluster/namespace as Levels 1–6 and reach EFM via ordinary cluster-internal
DNS (`efm.cld-streaming.svc:10090`), since this playground's Minikube cluster and the
`cld-streaming` cluster EFM runs in are the same cluster. Full build story, API contract used, and
field-verification details: BrainShare's `minifi-playground-efm-level2.md`.