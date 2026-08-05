# Sample Gallery of MiNiFi Flows

A curated, runnable set of MiNiFi flows you can lift and adapt. Each entry is a flow that has
been **field-validated** somewhere in the [Complete Guide to Edge Flow Management](https://github.com/cldr-steven-matison/DesktopShare/blob/main/Complete%20Guide%20to%20Edge%20Flow%20Management.md)
work — this gallery collects and polishes them behind one consistent card, it doesn't invent new
ones. A flow only earns a full card here **after its own chapter is ✅ field-validated**; the
candidates that haven't cleared that bar yet are listed as pending slots at the bottom.

Scope note: the golden-source plan for this gallery is
[`minifi-sample-gallery.md`](https://github.com/cldr-steven-matison/DesktopShare/blob/main/minifi-sample-gallery.md)
in DesktopShare (Ch18 of the guide). This `sample-gallery/` directory is the runnable home; that
doc is the plan.

## Card format

Every entry uses the same card so the gallery reads consistently:

- **Name** — short, googlable
- **Purpose** — one line, what it's for
- **Agent** — C++ / Java, version, class (standalone vs EFM-managed)
- **Shape** — the processor chain
- **Files** — `config.yml` and/or exported `flow.json`
- **Verification** — the exact command(s) to prove it runs

Configs live once, at the repo root (the repo's README-embeds-configs convention), and each card
links to them rather than duplicating — one source of truth, no drift. A flow that grows its own
dedicated artifacts gets its own subdir under `sample-gallery/`.

---

## Entry 1 — HTTP → Kafka + File (MiNiFi C++, standalone)

- **Name:** `http-to-kafka-cpp`
- **Purpose:** Accept an HTTP POST at the edge and fan it out to a Kafka topic *and* a local file in one flow.
- **Agent:** MiNiFi **C++** `1.26.02` (`container.repo.cloudera.com/cloudera/apacheminificpp:latest`), standalone `config.yml`, no EFM.
- **Shape:** fan-out from a single listener —
  ```
  ListenHTTP ─┬─(success)─→ PublishKafka   (topic test-minifi)
              └─(success)─→ PutFile         (/tmp/minifi-test-output)
  ```
  Both connections carry `ListenHTTP`'s `success` relationship; the flow is a fork, not a chain.
- **Files:** [`config.yml`](../config.yml) · [`Dockerfile`](../Dockerfile) · [`minifi-test.yaml`](../minifi-test.yaml) (NodePort 30080)
- **Verification:**
  ```bash
  # 1. tunnel to the agent
  minikube service minifi-test-service --url
  # 2. POST a payload (use the tunnel port from step 1)
  curl -i -X POST http://127.0.0.1:<TUNNEL_PORT>/contentListener \
       -H "Content-Type: application/json" \
       -d '{"test_id": "integration-success", "message": "Flow is functional"}'
  # 3. confirm it landed in Kafka
  kubectl run kafka-viewer -it --rm \
    --image=quay.io/strimzi/kafka:latest-kafka-3.7.0 --restart=Never \
    -- bin/kafka-console-consumer.sh \
    --bootstrap-server my-cluster-kafka-bootstrap.cld-streaming.svc:9092 \
    --topic test-minifi --from-beginning --timeout-ms 10000
  # 4. confirm it also hit local disk
  kubectl exec -it deployment/minifi-test -- /bin/sh -c "cat /tmp/minifi-test-output/*"
  ```
- **Status:** ✅ field-validated (playground Minikube, context `minikube`). Full walkthrough in the
  [playground readme](../readme.md) §1–3.

---

## Entry 2 — HTTP → File (MiNiFi Java, standalone)

- **Name:** `http-to-file-java`
- **Purpose:** The Java-flavor counterpart — accept an HTTP POST and persist it to a local file. No Kafka (the stock Java image ships no Kafka NAR).
- **Agent:** MiNiFi **Java** `1.23.04-b15` (`container.repo.cloudera.com/cloudera/nifi-minifi-java:latest`), standalone `config-java.yml` (`MiNiFi Config Version: 3`), no EFM.
- **Shape:**
  ```
  ListenHTTP ─(success)─→ PutFile   (/tmp/minifi-test-output)
  ```
- **Files:** [`config-java.yml`](../config-java.yml) · [`Dockerfile.java`](../Dockerfile.java) · [`minifi-test-java.yaml`](../minifi-test-java.yaml) (NodePort 30081)
- **Verification:**
  ```bash
  minikube service minifi-test-java-service --url
  curl -i -X POST http://127.0.0.1:<TUNNEL_PORT>/contentListener \
       -H "Content-Type: application/json" \
       -d '{"test_id": "integration-success", "message": "Flow is functional"}'
  kubectl exec -it deployment/minifi-test-java -- /bin/sh -c "cat /tmp/minifi-test-output/*"
  ```
- **Status:** ✅ field-verified end-to-end 2026-07-29 (playground Minikube). Full walkthrough in the
  [playground readme](../readme.md) §4–6.
- **Gotchas worth lifting:** connections wire by `source id`/`destination id` (UUID), not by name;
  processor `class` is fully-qualified; the readiness/liveness probes must be `tcpSocket`, not
  `httpGet` — Java's `ListenHTTP` returns `405` to a bare `GET` and an `httpGet` probe crash-loops
  the pod.

---

## Entry 3 — Custom Python Processors (C++ & Java, EFM-managed)

- **Name:** `python-processors`
- **Purpose:** author a *new processor type* in Python — loaded by the agent under its own name,
  wired like any stock processor. **Not** `ExecuteScript`. Two runnable recipes: function-style on
  C++ and class-style (py4j) on Java.
- **Agent:** MiNiFi **C++** `1.26.02` (proven arm64 / x86_64 / Windows MSI / Jetson) and MiNiFi
  **Java** CEM `2.24.08.0-19` (py4j) — both EFM-managed.
- **Shape:** `ListenHTTP → EdgeTagger|EdgeJavaTagger → LogAttribute`.
- **Files:** [`python-processors/`](python-processors/) — per-recipe `.py`, the properties /
  `bootstrap.conf` snippet, the published EFM flow export, and (Java) a one-`apply` disposable
  agent pod. Full scenario doc: [`python-processors/README.md`](python-processors/README.md).
- **The unlock worth lifting:** on **Java**, Python is gated by `nifi.python.command`, which must
  be set in **`bootstrap.conf`** (MiNiFi-Java regenerates `minifi.properties` from it every start;
  a direct edit is wiped, and the C2 property-push is denylisted) *and* needs a `python3` added to
  the image. On **C++**, deliver the `.py` as an EFM Resource into the asset dir (function-style
  only).
- **Status:** ✅ field-validated end-to-end 2026-08-04 (all 6 platform legs; Java on a disposable
  minikube agent, 3/3 POSTs, no drops). Chapter:
  [Ch6](https://github.com/cldr-steven-matison/DesktopShare/blob/main/guide/ch06-minifi-custom-python-processors.md).

---

## Pending entries (harvest as each chapter validates)

These are the candidates named in the Ch18 plan and elsewhere in the guide. Each becomes a full
card above **only after its own chapter is ✅ field-validated** — listed here so the gallery's
target shape is visible while the work lands.

| Candidate | Source / gating work | State |
|---|---|---|
| EFM-managed C++ / Java (Level 2) | this repo, readme §7 (`PlaygroundCpp` / `PlaygroundJava`); `minifi-playground-efm-level2.md` | built — promote once re-confirmed under the gallery card |
| ExecuteScript Python transform | `efm-executescript.md` | pending chapter validation |
| Site-to-Site source flows (one per path) | `minifi-site-to-site.md` (Ch10–14, #30) | Ch10 scoped, live build deferred |
| Edge-AI router | How to AI with MiNiFi / edge-AI router case study | pending |
| TensorRT inference on Jetson | EFM + NVIDIA Jetson use case | pending |
| SparkPlug / MQTT ingest | SparkPlug demo | pending |

---

*Gallery scaffolded 2026-07-31 (FTF3XR2065, issue [#32](https://github.com/cldr-steven-matison/DesktopShare/issues/32)). Seeded with the two standalone flavors that are field-validated in this repo today; it accumulates as more chapters land.*
