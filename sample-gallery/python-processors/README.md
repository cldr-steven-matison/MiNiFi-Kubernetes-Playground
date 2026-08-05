# Custom Python Processors at the Edge

A **custom Python processor** is a *new processor type* you write in Python: the MiNiFi agent loads
it under its own name, with its own properties and relationships, and you wire it into a flow like
any stock processor. It is **not** `ExecuteScript` — that's one generic processor you paste a script
body into. A custom processor shows up as its own type in the agent manifest, and a type-signature
change needs an agent restart rather than re-reading on every trigger.

Two runnable recipes here — one C++, one Java. They differ in how the `.py` is authored and how the
Python interpreter is turned on.

---

## Recipe 1 — C++ (`apacheminificpp` 1.26.02)

- **Name:** `edge-tagger-cpp`
- **Purpose:** stamp every FlowFile with a fixed `edge.tag` attribute — the minimal proof that an
  authored `.py` loads as a first-class processor type on a C++ agent.
- **Agent:** MiNiFi **C++** `1.26.02`, EFM-managed. Runs on Linux arm64 / x86_64, Windows MSI, and
  Jetson aarch64.
- **Style:** **function-style** (`minifi_native`) — module-level `describe()` / `onInitialize()` /
  `onTrigger()`, **no `nifiapi` import**. This is the style that survives EFM-Resources
  (asset-directory) delivery.
- **Shape:** `ListenHTTP → EdgeTagger → LogAttribute` (or `PutFile`). Set ListenHTTP
  **Batch/Buffer Size = 1** (MINIFICPP-2243) or single requests are dropped.
- **Files:** [`cpp/EdgeTagger.py`](cpp/EdgeTagger.py),
  [`cpp/minifi.properties.snippet`](cpp/minifi.properties.snippet) (the one deliberate override is
  `nifi.python.processor.dir` pointed into the asset dir),
  [`cpp/EdgeTagger-flow-export.json`](cpp/EdgeTagger-flow-export.json).
- **Delivery:** upload `EdgeTagger.py` as an **EFM Resource** → it syncs into the agent's asset
  directory → the agent registers it as a type at boot. (Function-style only — a `nifiapi`
  class-style processor loses the framework package under this delivery and needs direct placement
  into `minifi-python/`.)
- **Verification:** POST a payload to the listener; confirm `edge.tag` lands at `LogAttribute` and
  no drops.

## Recipe 2 — Java (CEM `2.24.08.0-19`, py4j)

- **Name:** `edge-java-tagger`
- **Purpose:** the same idea on the **py4j-based** Java framework — custom Python processors are not
  C++-only.
- **Agent:** MiNiFi **Java** (CEM `2.24.08.0-19`, NiFi 2.x-based, ships the py4j framework),
  EFM-managed.
- **Style:** **class-style** subclassing `nifiapi.flowfiletransform.FlowFileTransform`, with the
  Java-bridge requirement `class Java: implements = ['org.apache.nifi.python.processor.FlowFileTransform']`.
- **Shape:** `ListenHTTP → EdgeJavaTagger → LogAttribute`.
- **Files:** [`java/EdgeJavaTagger.py`](java/EdgeJavaTagger.py),
  [`java/bootstrap.conf.snippet`](java/bootstrap.conf.snippet),
  [`java/EdgeJavaTagger-flow-export.json`](java/EdgeJavaTagger-flow-export.json),
  [`java/minifi-java-pytest-pod.yaml`](java/minifi-java-pytest-pod.yaml) (a self-contained agent —
  one `kubectl apply` brings it up Python-enabled).
- **The two things that turn Python on** (both required):
  1. **A `python3` interpreter must be in the image** — the stock MiNiFi-Java image ships none.
  2. **`nifi.python.command` must be set in `bootstrap.conf`, not `minifi.properties`.** MiNiFi-Java
     regenerates `minifi.properties` from `bootstrap.conf` on every start, so a direct properties
     edit is wiped; and the EFM C2 `UPDATE_PROPERTIES` push of it is denylisted. `bootstrap.conf`
     is the durable channel.
- **Verification:** the boot log shows `Discovered Python Processor EdgeJavaTagger` and the gate
  line (`Python Extensions disabled because the nifi.python.command property...`) is **absent**;
  POST a payload, confirm `edge.java.tag` lands at `LogAttribute`, no drops.

> **Note on this playground's Java flavor.** The root `Dockerfile.java` builds the older
> `nifi-minifi-java:latest` (`1.23.04-b15`, NiFi **1.x**), which has **no py4j Python framework** — so
> Recipe 2 does *not* run on that image. It targets the CEM `2.24.08.0-19` (NiFi 2.x) tarball, which
> `java/minifi-java-pytest-pod.yaml` pulls in via the EFM agent-deployer. Keep the two Java runtimes
> straight.

---

## Building the flow programmatically

Both flows are built through the **EFM Designer API** (`POST .../processors`, `POST
.../connections`, `GET .../validate`, `POST .../publish`) — not a hand-written `config.yml`. One
gotcha bites every new type: after the agent first registers it, EFM keeps serving the class's old
manifest. Re-point the class at the new manifest (`PUT /agent-classes/{name}`, confirm with
`.../manifest-diff`), then **delete and recreate** the processor component so its property
descriptors re-resolve. After that, validation is clean and publish pushes to the agent on its next
heartbeat.
