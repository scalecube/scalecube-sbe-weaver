# scalecube-sbe-weaver

> Generate a clean, typed Java SDK from an SBE schema and a YAML service descriptor.
> No encoders, decoders, or byte buffers ever appear in your application code.

[![Build](https://github.com/scalecube/scalecube-sbe-weaver/actions/workflows/branch-ci.yml/badge.svg)](https://github.com/scalecube/scalecube-sbe-weaver/actions)
[![Java](https://img.shields.io/badge/Java-17%2B-blue)](https://openjdk.org/projects/jdk/17/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## Table of contents

1. [What is SBE?](#1-what-is-sbe)
2. [The problem — before vs after](#2-the-problem--before-vs-after)
3. [How it works — the pipeline](#3-how-it-works--the-pipeline)
4. [Module structure](#4-module-structure)
5. [Prerequisites](#5-prerequisites)
6. [Quick start](#6-quick-start)
7. [pom.xml setup — annotated](#7-pomxml-setup--annotated)
8. [Writing your SBE schema](#8-writing-your-sbe-schema)
9. [Writing your service YAML — full reference](#9-writing-your-service-yaml--full-reference)
10. [Operation styles](#10-operation-styles)
11. [Generated output — complete walkthrough](#11-generated-output--complete-walkthrough)
12. [The Transport interface](#12-the-transport-interface)
13. [Error handling patterns](#13-error-handling-patterns)
14. [The example module — full showcase](#14-the-example-module--full-showcase)
15. [Type mapping reference](#15-type-mapping-reference)
16. [Naming conventions](#16-naming-conventions)
17. [Best practices](#17-best-practices)
18. [What is NOT supported](#18-what-is-not-supported)
19. [Troubleshooting](#19-troubleshooting)
20. [Contributing — adding a new operation style](#20-contributing--adding-a-new-operation-style)

---

## 1. What is SBE?

**Simple Binary Encoding (SBE)** is a zero-copy, fixed-format binary serialisation protocol
designed for ultra-low-latency messaging. It is widely used in high-performance systems where
latency and throughput matter — matching engines, market data feeds, trading infrastructure,
gaming servers, IoT platforms, and anywhere you need to push millions of messages per second
with predictable latency.

The SBE tool (`uk.co.real_logic.sbe.SbeTool`) takes an XML schema and generates Java source
files like `PlaceOrderRequestEncoder.java` and `PlaceOrderRequestDecoder.java`. These classes
let you read and write binary frames very efficiently, but they are inherently **low-level**:
every call site must manually manage buffers, wrap headers, check template IDs, and decode
fields one by one.

**scalecube-sbe-weaver** is a code-generation layer that sits on top of the SBE-generated
codecs and produces a clean, typed, idiomatic Java SDK from them — so that the rest of your
code never needs to know SBE exists.

---

## 2. The problem — before vs after

### Without scalecube-sbe-weaver — raw SBE

Every place you want to make a request you write something like this:

```java
// 1. Allocate a buffer
ExpandableArrayBuffer buf = new ExpandableArrayBuffer(256);

// 2. Wrap the encoder and write the SBE header
PlaceOrderRequestEncoder enc = new PlaceOrderRequestEncoder();
MessageHeaderEncoder hdrEnc  = new MessageHeaderEncoder();
enc.wrapAndApplyHeader(buf, 0, hdrEnc);

// 3. Set fields one by one — order matters, no type safety on composites
enc.clientOrderId(clientOrderId);
enc.instrumentId(instrumentId);
enc.price(price);
enc.quantity(quantity);
enc.side(Side.Buy);
enc.timeInForce(TimeInForce.GoodTillCancel);

// 4. Calculate frame length and send
int len = MessageHeaderEncoder.ENCODED_LENGTH + enc.encodedLength();
DirectBuffer reply = transport.request(new UnsafeBuffer(buf, 0, len), 0, len);

// 5. Unwrap the reply — check which message came back
MessageHeaderDecoder hdrDec = new MessageHeaderDecoder().wrap(reply, 0);
int templateId = hdrDec.templateId();
int bodyOffset = hdrDec.encodedLength();

if (templateId == PlaceOrderResponseDecoder.TEMPLATE_ID) {
    PlaceOrderResponseDecoder dec = new PlaceOrderResponseDecoder()
        .wrap(reply, bodyOffset,
              PlaceOrderResponseDecoder.BLOCK_LENGTH,
              PlaceOrderResponseDecoder.SCHEMA_VERSION);
    long orderId = dec.orderId();
    // ...
} else if (templateId == PlaceOrderFailedDecoder.TEMPLATE_ID) {
    PlaceOrderFailedDecoder dec = new PlaceOrderFailedDecoder()
        .wrap(reply, bodyOffset,
              PlaceOrderFailedDecoder.BLOCK_LENGTH,
              PlaceOrderFailedDecoder.SCHEMA_VERSION);
    throw new RuntimeException("error: " + dec.errorCode());
} else {
    throw new IllegalStateException("Unexpected templateId: " + templateId);
}
```

That is 30+ lines for a single request. Every service, every operation.

### With scalecube-sbe-weaver — generated SDK

```java
PlaceOrderResponse response = api.placeOrder(req -> req
    .clientOrderId(clientOrderId)
    .instrumentId(instrumentId)
    .price(price)
    .quantity(quantity)
    .side(Side.Buy)
    .timeInForce(TimeInForce.GoodTillCancel));
```

Five lines. Fully typed. Throws a typed exception on error. The 30 lines of SBE boilerplate
are generated once and live in files you never touch.

---

## 3. How it works — the pipeline

```
┌─────────────────────────┐
│  your-schema.xml        │──► sbe-tool ──► XxxEncoder.java
│  (SBE message schema)   │               XxxDecoder.java
└─────────────────────────┘               MessageHeaderEncoder.java
                                          MessageHeaderDecoder.java
                    ▲ reflects on compiled codecs at generate-sources time
                    │
┌─────────────────────────┐
│  your-service.yaml      │──► SdkGenerator ──► YourApi.java            (interface)
│  (service descriptor)   │                     YourApiImpl.java         (implementation)
└─────────────────────────┘                     PlaceOrderRequest.java   (request builder)
                                                PlaceOrderResponse.java  (result POJO)
                                                PlaceOrderFailed.java    (typed exception)
                                                PlaceOrderRequestMapper.java   (internal)
                                                PlaceOrderResponseMapper.java  (internal)
                                                Side.java                (enum copy)
```

**Key insight:** The generator does not parse the SBE XML directly. Instead it runs *after*
`sbe-tool` has already compiled the codecs and uses Java reflection to inspect them. This
means it automatically picks up every field, its type, whether it is optional, and any
composite sub-fields — without duplicating the schema parsing logic.

The three build phases (all automatic via Maven):

| Phase | Tool | Input | Output |
|---|---|---|---|
| `generate-sources` | `sbe-tool` | `your-schema.xml` | SBE encoder/decoder `.java` files |
| `generate-sources` | `SdkGenerator` | compiled SBE codecs + `your-service.yaml` | SDK `.java` files |
| `compile` | `javac` | everything above | compiled `.class` files |

---

## 4. Module structure

```
scalecube-sbe-weaver/
├── generator/          io.scalecube:scalecube-sbe-weaver-generator
│                       The generator itself. Parses the YAML, reflects on SBE codecs,
│                       emits Java source files. Build-time only — not a runtime dependency.
│
├── sdk/                io.scalecube:scalecube-sbe-weaver-sdk
│                       The runtime library your generated code depends on.
│                       Contains: Transport interface, SdkException base class.
│                       This is the only scalecube artifact you need at runtime.
│
├── example-sbe/        io.scalecube:scalecube-sbe-weaver-example-sbe
│                       A generic OrderService SBE schema compiled into codec classes.
│                       Used by the example module. Shows how to set up sbe-tool in Maven.
│
└── example/            io.scalecube:scalecube-sbe-weaver-example
                        A complete working example: order-service.yaml + generated SDK + tests.
                        Read this if you want to see everything working end-to-end.
```

**Build order matters.** Maven builds these modules in the order shown. The `example` module
depends on the codecs produced by `example-sbe`, which must exist before the generator runs.

**What you depend on in your project:**

```xml
<!-- Runtime: the Transport interface and SdkException -->
<dependency>
  <groupId>io.scalecube</groupId>
  <artifactId>scalecube-sbe-weaver-sdk</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

The generator itself is only needed at build time (inside the exec-maven-plugin configuration).
It is never on your runtime classpath.

---

## 5. Prerequisites

Before you start you need:

- **Java 17 or higher.** The generated code uses modern Java features and the generator
  requires Java 17. Check with `java -version`.

- **Maven 3.8 or higher.** Check with `mvn -version`.

- **An SBE schema.** You need an XML file describing your messages in SBE format. If you
  don't have one yet, see [Writing your SBE schema](#8-writing-your-sbe-schema) and the
  [SBE specification](https://github.com/real-logic/simple-binary-encoding).

- **The scalecube-sbe-weaver artifacts installed in your local Maven repository.**
  Either build from source (`mvn install` from the repo root) or pull from GitHub Packages
  (see [pom.xml setup](#7-pomxml-setup--annotated) for repository configuration).

- **Your SBE schema compiled by sbe-tool first.** The generator reflects on the compiled
  codec classes — it cannot run until `sbe-tool` has produced them. In a properly configured
  Maven pom this happens automatically in the right order.

---

## 6. Quick start

This gets you from zero to a working generated SDK in about 15 minutes.

### Step 1 — Put your SBE schema in place

```
src/
  main/
    resources/
      sbe/
        my-schema.xml      ← your SBE schema here
      my-service.yaml      ← your YAML descriptor here (next step)
```

### Step 2 — Write a minimal YAML descriptor

```yaml
service: GreeterApi
package: com.example.greeter.api

operations:
  - name: sayHello
    style: requestOne
    command: SayHelloRequest
    reply:   SayHelloResponse
```

`SayHelloRequest` and `SayHelloResponse` must be message names in your SBE schema.

### Step 3 — Add the Maven configuration

See [pom.xml setup](#7-pomxml-setup--annotated) for the full annotated snippet.
At minimum you need:
- `exec-maven-plugin` to run sbe-tool
- `exec-maven-plugin` to run SdkGenerator
- `build-helper-maven-plugin` to register the generated sources

### Step 4 — Generate

```bash
mvn generate-sources
```

Look in `target/generated-sources/sdk/com/example/greeter/api/` — you should see:

```
GreeterApi.java
GreeterApiImpl.java
SayHelloRequest.java
SayHelloResponse.java
SayHelloRequestMapper.java
SayHelloResponseMapper.java
```

### Step 5 — Use the generated API

```java
// Wire up your Transport implementation (see section 12)
GreeterApi api = new GreeterApiImpl(myTransport);

// Call it — no encoders, no buffers, no SBE imports
SayHelloResponse response = api.sayHello(req -> req.name("world"));

assertThat(response.greeting()).isEqualTo("Hello, world!");
```

---

## 7. pom.xml setup — annotated

Below is a complete `pom.xml` for a project that uses scalecube-sbe-weaver.
Every non-obvious line is explained.

```xml
<properties>
  <!-- Version of scalecube-sbe-weaver to use -->
  <scalecube.sbe.weaver.version>0.1.0-SNAPSHOT</scalecube.sbe.weaver.version>

  <!--
    The Java package prefix where your SBE-generated codecs live.
    This is the `package` attribute in your SBE schema's <sbe:messageSchema> element.
    The generator scans this package (and all sub-packages) via reflection to find
    encoder/decoder classes. Must match exactly.
  -->
  <sbe.root.package>com.example.sbe</sbe.root.package>

  <!-- Versions of dependencies that must match the scalecube-sbe-weaver release -->
  <agrona.version>2.2.4</agrona.version>
  <snakeyaml.version>2.2</snakeyaml.version>
  <reactor.version>3.7.8</reactor.version>
</properties>

<!--
  If you are pulling from GitHub Packages you need this repository declaration
  AND a ~/.m2/settings.xml entry with a GitHub token:
    <server>
      <id>github-scalecube</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_GITHUB_TOKEN</password>
    </server>
-->
<repositories>
  <repository>
    <id>github-scalecube</id>
    <url>https://maven.pkg.github.com/scalecube/scalecube-sbe-weaver</url>
  </repository>
</repositories>

<dependencies>
  <!--
    Runtime dependency: Transport interface + SdkException.
    Your generated code imports from this artifact.
    Must be on the compile AND runtime classpath.
  -->
  <dependency>
    <groupId>io.scalecube</groupId>
    <artifactId>scalecube-sbe-weaver-sdk</artifactId>
    <version>${scalecube.sbe.weaver.version}</version>
  </dependency>

  <!--
    Your SBE codecs JAR — the artifact that sbe-tool produced for your schema.
    Mark as `provided` if another module in the same reactor compiles the codecs;
    mark as `compile` if the codecs are a separate published artifact.
    The generator needs to find these classes via reflection at build time.
  -->
  <dependency>
    <groupId>com.example</groupId>
    <artifactId>my-sbe-codecs</artifactId>
    <version>${my.codecs.version}</version>
    <scope>provided</scope>
  </dependency>

  <!-- Agrona: DirectBuffer etc., used by the generated impl -->
  <dependency>
    <groupId>org.agrona</groupId>
    <artifactId>agrona</artifactId>
    <version>${agrona.version}</version>
  </dependency>

  <!-- Project Reactor: Mono and Flux used in the generated API -->
  <dependency>
    <groupId>io.projectreactor</groupId>
    <artifactId>reactor-core</artifactId>
    <version>${reactor.version}</version>
  </dependency>
</dependencies>

<build>
  <plugins>

    <plugin>
      <groupId>org.codehaus.mojo</groupId>
      <artifactId>exec-maven-plugin</artifactId>
      <version>3.1.1</version>
      <executions>

        <!--
          Run SdkGenerator to emit the typed Java SDK.

          This runs during `generate-sources`, which is BEFORE `compile`.
          It must run AFTER sbe-tool has already produced the codec classes,
          which means either:
            a) Your codecs are in a separate Maven module that was built first, OR
            b) sbe-tool runs in an earlier exec execution in the same module.

          Arguments (in order):
            1. sbe.root.package  — the package to scan for SBE codecs via reflection
            2. yaml directory    — directory containing your *.yaml service descriptors
            3. output directory  — where to write the generated .java files

          The generator processes ALL *.yaml files in the directory.
          Files that reference messages not found in the SBE schema fail with a clear error.
        -->
        <execution>
          <id>generate-sdk-sources</id>
          <phase>generate-sources</phase>
          <goals><goal>java</goal></goals>
          <configuration>
            <mainClass>io.scalecube.sbe.codegen.generator.SdkGenerator</mainClass>
            <arguments>
              <argument>${sbe.root.package}</argument>
              <argument>${project.basedir}/src/main/resources</argument>
              <argument>${project.build.directory}/generated-sources/sdk</argument>
            </arguments>

            <!--
              The generator needs these JARs on its classpath at generation time.
              We load them from the local Maven repository by path because the generator
              runs in the same JVM as Maven (exec:java goal) rather than as a forked
              process with a full Maven classpath.

              You need:
                1. scalecube-sbe-weaver-generator — the generator itself
                2. snakeyaml — for YAML parsing
                3. your SBE codecs JAR — for reflection
                4. agrona — for DirectBuffer etc.
            -->
            <additionalClasspathElements>
              <additionalClasspathElement>
                ${settings.localRepository}/io/scalecube/scalecube-sbe-weaver-generator/${scalecube.sbe.weaver.version}/scalecube-sbe-weaver-generator-${scalecube.sbe.weaver.version}.jar
              </additionalClasspathElement>
              <additionalClasspathElement>
                ${settings.localRepository}/org/yaml/snakeyaml/${snakeyaml.version}/snakeyaml-${snakeyaml.version}.jar
              </additionalClasspathElement>
              <additionalClasspathElement>
                ${settings.localRepository}/com/example/my-sbe-codecs/${my.codecs.version}/my-sbe-codecs-${my.codecs.version}.jar
              </additionalClasspathElement>
              <additionalClasspathElement>
                ${settings.localRepository}/org/agrona/agrona/${agrona.version}/agrona-${agrona.version}.jar
              </additionalClasspathElement>
            </additionalClasspathElements>

            <!--
              Do NOT include project dependencies — the generator needs only the
              JARs listed above, not your application's full classpath.
            -->
            <includeProjectDependencies>false</includeProjectDependencies>
            <includePluginDependencies>false</includePluginDependencies>
          </configuration>
        </execution>

      </executions>
    </plugin>

    <!--
      Register the generated SDK sources as a source root so Maven
      compiles them during the `compile` phase.
      Without this plugin, Maven will not know the generated files exist.
    -->
    <plugin>
      <groupId>org.codehaus.mojo</groupId>
      <artifactId>build-helper-maven-plugin</artifactId>
      <version>3.5.0</version>
      <executions>
        <execution>
          <id>add-generated-sources</id>
          <phase>generate-sources</phase>
          <goals><goal>add-source</goal></goals>
          <configuration>
            <sources>
              <source>${project.build.directory}/generated-sources/sdk</source>
            </sources>
          </configuration>
        </execution>
      </executions>
    </plugin>

  </plugins>
</build>
```

---

## 8. Writing your SBE schema

The generator does not validate your SBE schema — that is `sbe-tool`'s job. What the
generator cares about is the structure of the **compiled** encoder and decoder classes.

### Schema structure requirements

Your schema must declare a `messageHeader` composite named exactly `messageHeader`.
This is the standard SBE requirement:

```xml
<sbe:messageSchema xmlns:sbe="http://fixprotocol.io/2016/sbe"
    package="com.example.sbe"
    id="1"
    version="0">

  <types>
    <composite name="messageHeader">
      <type name="blockLength" primitiveType="uint16"/>
      <type name="templateId"  primitiveType="uint16"/>
      <type name="schemaId"    primitiveType="uint16"/>
      <type name="version"     primitiveType="uint16"/>
    </composite>
    <!-- your other types here -->
  </types>

  <!-- your messages here -->

</sbe:messageSchema>
```

The `package` attribute on `<sbe:messageSchema>` must match the `sbe.root.package` property
in your pom.xml. The generator scans this package to find encoder and decoder classes.

### What the generator understands

| SBE construct | Example | Generator behaviour |
|---|---|---|
| Primitive field | `<field name="orderId" id="1" type="uint64"/>` | Mapped to Java type (see §15) |
| Optional primitive | `<field ... presence="optional"/>` | Mapped to boxed nullable type |
| Enum field | `<field name="side" id="5" type="Side"/>` | Enum copied into API package |
| Decimal composite | composite with `mantissa` + `exponent` | Mapped to `BigDecimal` |
| UUID composite | composite with `mostSignificantBits` + `leastSignificantBits` | Mapped to `java.util.UUID` |
| Char array | `<field name="name" type="charArray"/>` | Mapped to `String` |
| Variable-length data | `<data name="note" type="varStringEncoding"/>` | Mapped to `String` |
| Repeating group | `<group name="legs" ...>` | Mapped to `List<Leg>` with `addLeg(Consumer<Leg>)` builder |

### Naming tip

The generator uses the SBE message name directly as the Java class name. Use descriptive
names that read naturally as Java types:

```xml
<!-- Good: names read naturally as Java types -->
<message name="PlaceOrderRequest"  id="1"/>
<message name="PlaceOrderResponse" id="2"/>
<message name="PlaceOrderFailed"   id="3"/>

<!-- Avoid: names that don't read well as Java types -->
<message name="MSG_001" id="1"/>
<message name="place_order_cmd" id="2"/>
```

---

## 9. Writing your service YAML — full reference

A service YAML file describes one service: its Java interface name, package, and the list
of operations it exposes. Each operation maps to one or more methods on the generated interface.

### Top-level fields

```yaml
service: OrderApi          # Required. Name of the generated Java interface and Impl class.
                           # Will produce OrderApi.java and OrderApiImpl.java.

package: com.example.api   # Required. Java package for all generated files.
                           # Use a package that makes sense for your application layer —
                           # not the same package as the SBE codecs.

operations:                # Required. List of operations (at least one).
  - ...
```

### Operation fields

| Field | Required | Applies to styles | Description |
|---|---|---|---|
| `name` | Yes | all | Java method name. Use camelCase. |
| `style` | Yes | all | One of: `requestOne`, `requestMany`, `subscription`, `fireAndForget` |
| `command` | Conditional | requestOne, requestMany, fireAndForget, subscription (optional) | SBE message name for the outbound command |
| `reply` | requestOne | requestOne | SBE message name for the success reply |
| `onError` | No | requestOne | SBE message name for the error reply. The generated exception extends `SdkException`. |
| `event` | Conditional | requestMany, subscription | SBE message name for stream elements |
| `topic` | subscription | subscription | The topic string passed to `Transport.subscribe()` |
| `errorVariants` | No | requestOne | List of additional error-callback method overloads |

### `errorVariants` fields

```yaml
errorVariants:
  - name:  placeOrderUnsuccessfully   # Method name for this variant
    style: callbackErrorWithRequest   # callbackError or callbackErrorWithRequest
```

| Variant style | Generated signature |
|---|---|
| `callbackError` | `void name(Consumer<Cmd> spec, Consumer<ErrorType> onError)` |
| `callbackErrorWithRequest` | `void name(Consumer<Cmd> spec, BiConsumer<Cmd, ErrorType> onError)` |

Use `callbackErrorWithRequest` when the error handler needs to know which specific request
caused the error (e.g. for logging or retry logic). Use `callbackError` when only the error
itself is needed.

### Full annotated example

```yaml
service: OrderApi
package: com.example.order.api

operations:

  # ── requestOne ────────────────────────────────────────────────────────────
  # Sends a command, waits for exactly one reply (success or error).
  # Generates: sync method + Mono async method + optional error callback methods.

  - name: placeOrder
    style: requestOne
    command: PlaceOrderRequest        # outbound SBE message
    reply:   PlaceOrderResponse       # success reply SBE message
    onError: PlaceOrderFailed         # error reply SBE message → becomes typed exception
    errorVariants:
      - name:  placeOrderUnsuccessfully
        style: callbackErrorWithRequest   # callback receives (request, error)

  - name: cancelOrder
    style: requestOne
    command: CancelOrderRequest
    reply:   CancelOrderResponse
    onError: CancelOrderFailed
    errorVariants:
      - name:  cancelOrderUnsuccessfully
        style: callbackError              # callback receives (error) only

  - name: modifyOrder
    style: requestOne
    command: ModifyOrderRequest
    reply:   ModifyOrderResponse
    # no onError: the operation is considered infallible at the protocol level

  # ── fireAndForget ─────────────────────────────────────────────────────────
  # Sends a command and does not wait for any reply.
  # Generates: void method + Mono<Void> async method.

  - name: sendHeartbeat
    style: fireAndForget
    command: Heartbeat

  # ── requestMany ───────────────────────────────────────────────────────────
  # Sends a command and receives a stream of reply frames directly.
  # The stream is a direct reply to the command (not a topic subscription).
  # Generates: method returning Flux<EventType>.

  - name: getOrderHistory
    style: requestMany
    command: GetOrderHistoryRequest
    event:   OrderHistoryEntry        # type of each element in the stream

  # ── subscription (without command) ────────────────────────────────────────
  # Subscribes to a push topic. No command is sent.
  # Generates: no-argument method returning Flux<EventType>.

  - name: subscribeOrderPlaced
    style: subscription
    topic: order.events               # topic passed to Transport.subscribe()
    event: OrderPlaced                # templateId-filtered from the topic stream

  - name: subscribeOrderCancelled
    style: subscription
    topic: order.events               # multiple events can share a topic;
    event: OrderCancelled             # each subscription filters by its own templateId

  # ── subscription (with command) ───────────────────────────────────────────
  # Sends a register/filter command first, then subscribes to a push topic.
  # The server uses the command to determine what to push.
  # Generates: method taking Consumer<CmdType>, returning Flux<EventType>.

  - name: subscribeOrdersByInstrument
    style: subscription
    command: SubscribeOrdersRequest   # sent first to register the filter
    topic:   order.events             # then subscribe to this topic
    event:   OrderPlaced              # filtered by templateId within the topic
```

---

## 10. Operation styles

### `requestOne` — request / single reply

Use this for any operation that sends one command and expects exactly one reply (success
or typed error). This is the most common style.

**YAML fields:** `command` (required), `reply` (required), `onError` (optional),
`errorVariants` (optional, requires `onError`).

**Generated methods:**

```java
// Synchronous — blocks until reply arrives, throws onError exception on failure
PlaceOrderResponse placeOrder(Consumer<PlaceOrderRequest> spec);

// Asynchronous — returns immediately with a Mono that resolves on reply
Mono<PlaceOrderResponse> placeOrderAsync(Consumer<PlaceOrderRequest> spec);

// Error callback variant (callbackErrorWithRequest)
// Does not throw — calls onError callback, passing both request and error
void placeOrderUnsuccessfully(Consumer<PlaceOrderRequest> spec,
                              BiConsumer<PlaceOrderRequest, PlaceOrderFailed> onError);

// Error callback variant (callbackError)
// Does not throw — calls onError callback with the error only
void cancelOrderUnsuccessfully(Consumer<CancelOrderRequest> spec,
                               Consumer<CancelOrderFailed> onError);
```

**How it works internally:**
The impl encodes the command into an SBE frame, calls `transport.request()`, then inspects
the reply's `templateId`. If it matches the reply message, the reply is decoded into the
result POJO. If it matches the error message, the error is decoded into the typed exception
and thrown (or passed to the callback). Any other templateId throws `IllegalStateException`.

---

### `requestMany` — request / stream of replies

Use this when you send one command and the server responds with a **finite stream of frames**
delivered directly back through the transport. Think of it as a server-streaming RPC.

**YAML fields:** `command` (required), `event` (required). No `reply`, no `topic`.

**Generated method:**

```java
// Sends the command, returns a Flux of decoded elements
Flux<OrderHistoryEntry> getOrderHistory(Consumer<GetOrderHistoryRequest> spec);
```

**How it works internally:**
The impl encodes the command and calls `transport.requestMany()`. The returned
`Flux<DirectBuffer>` is filtered by the event's `TEMPLATE_ID` (so mixed-type streams are
handled safely), then each matching frame is decoded into the event POJO.

**When to use `requestMany` vs `subscription`:**
- `requestMany`: the stream is a **direct reply** to your command — it comes back through
  the same transport channel. The stream is typically finite (server sends all results,
  then completes). Example: "give me all records matching these criteria".
- `subscription`: events are pushed on a **named topic**. The stream is typically infinite
  and ongoing. Example: "notify me whenever a new event occurs".

---

### `subscription` — topic-based push stream

Use this to receive an ongoing stream of events published to a named topic.

Two variants depending on whether a registration command is needed:

**Without command** — purely passive subscription:

```yaml
- name: subscribeOrderPlaced
  style: subscription
  topic: order.events
  event: OrderPlaced
```

```java
// No parameters — just subscribe and receive
Flux<OrderPlaced> subscribeOrderPlaced();
```

**With command** — send a register/filter message first, then receive push events:

```yaml
- name: subscribeOrdersByInstrument
  style: subscription
  command: SubscribeOrdersRequest
  topic:   order.events
  event:   OrderPlaced
```

```java
// Takes a spec to configure the registration command
Flux<OrderPlaced> subscribeOrdersByInstrument(Consumer<SubscribeOrdersRequest> spec);
```

**How it works internally (without command):**
Calls `transport.subscribe(topic)`, filters the resulting `Flux<DirectBuffer>` by the
event message's `TEMPLATE_ID`, then maps each matching frame to the decoded event POJO.

**How it works internally (with command):**
Encodes the registration command, calls `transport.request()` (discarding the reply),
then calls `transport.subscribe(topic)`, filters by `TEMPLATE_ID`, and maps to the POJO.

**Important — templateId filtering:**
When multiple event types share the same topic (e.g. `OrderPlaced`, `OrderCancelled`, and
`OrderExecuted` all published on `order.events`), each subscription method correctly filters
to only its own event type using the SBE `TEMPLATE_ID` constant. You can safely have multiple
subscriptions on the same topic.

---

### `fireAndForget` — send and move on

Use this when you need to send a command but do not need to wait for or process any reply.
The server may or may not send a reply; the generated code ignores it either way.

**YAML fields:** `command` (required).

**Generated methods:**

```java
// Synchronous send — blocks until the frame is written to the transport
void sendHeartbeat(Consumer<Heartbeat> spec);

// Asynchronous send — returns a Mono<Void> that completes when the frame is written
// Use this in reactive pipelines to avoid blocking
Mono<Void> sendHeartbeatAsync(Consumer<Heartbeat> spec);
```

---

## 11. Generated output — complete walkthrough

Given the operation:

```yaml
- name: placeOrder
  style: requestOne
  command: PlaceOrderRequest
  reply:   PlaceOrderResponse
  onError: PlaceOrderFailed
  errorVariants:
    - name:  placeOrderUnsuccessfully
      style: callbackErrorWithRequest
```

The generator produces the following files.

### `PlaceOrderRequest.java` — the request builder

A fluent, mutable builder. You fill it in using the `Consumer<PlaceOrderRequest>` lambda
pattern. Fields map directly to SBE message fields with Java type conversions applied.

```java
public final class PlaceOrderRequest {

  long clientOrderId;
  int  instrumentId;
  long price;
  long quantity;
  Side side;
  TimeInForce timeInForce;

  public PlaceOrderRequest clientOrderId(long v)      { this.clientOrderId = v; return this; }
  public PlaceOrderRequest instrumentId(int v)        { this.instrumentId  = v; return this; }
  public PlaceOrderRequest price(long v)              { this.price         = v; return this; }
  public PlaceOrderRequest quantity(long v)           { this.quantity      = v; return this; }
  public PlaceOrderRequest side(Side v)               { this.side          = v; return this; }
  public PlaceOrderRequest timeInForce(TimeInForce v) { this.timeInForce   = v; return this; }
}
```

**You never instantiate this class directly.** You always receive it as the parameter in a
`Consumer<PlaceOrderRequest>` lambda:

```java
api.placeOrder(req -> req
    .clientOrderId(42L)
    .instrumentId(1)
    .price(10000L)
    .quantity(100L)
    .side(Side.Buy)
    .timeInForce(TimeInForce.GoodTillCancel));
```

### `PlaceOrderResponse.java` — the immutable result POJO

Decoded from the SBE reply frame. All fields are `final`. Access via getter methods.

```java
public final class PlaceOrderResponse {

  private final long orderId;
  private final long clientOrderId;
  private final OrderStatus status;

  PlaceOrderResponse(long orderId, long clientOrderId, OrderStatus status) { ... }

  public long orderId()       { return orderId; }
  public long clientOrderId() { return clientOrderId; }
  public OrderStatus status() { return status; }
}
```

### `PlaceOrderFailed.java` — the typed exception

Extends `SdkException` (which extends `RuntimeException`). Thrown by the sync method,
or emitted as an error by the async `Mono`.

```java
public final class PlaceOrderFailed extends SdkException {

  private final long clientOrderId;
  private final int  errorCode;
  private final String errorMessage;

  PlaceOrderFailed(long clientOrderId, int errorCode, String errorMessage) {
    super(clientOrderId + ": " + errorCode + ": " + errorMessage);
    this.clientOrderId = clientOrderId;
    this.errorCode     = errorCode;
    this.errorMessage  = errorMessage;
  }

  public long   clientOrderId() { return clientOrderId; }
  public int    errorCode()     { return errorCode; }
  public String errorMessage()  { return errorMessage; }
}
```

### `OrderApi.java` — the service interface

```java
public interface OrderApi {

  PlaceOrderResponse placeOrder(Consumer<PlaceOrderRequest> spec); // throws PlaceOrderFailed

  Mono<PlaceOrderResponse> placeOrderAsync(Consumer<PlaceOrderRequest> spec);

  void placeOrderUnsuccessfully(Consumer<PlaceOrderRequest> spec,
                                BiConsumer<PlaceOrderRequest, PlaceOrderFailed> onError);
  // ... other operations
}
```

### `PlaceOrderRequestMapper.java` — internal (never edit)

Package-private. Generated code that encodes your `PlaceOrderRequest` POJO into an SBE
frame using `PlaceOrderRequestEncoder`. Handles all type conversions, optional fields,
repeating groups, and varData. You never call this class directly.

### `PlaceOrderResponseMapper.java` — internal (never edit)

Package-private. Decodes an SBE frame using `PlaceOrderResponseDecoder` into a
`PlaceOrderResponse` POJO. Never called directly.

### `OrderApiImpl.java` — the implementation

```java
public final class OrderApiImpl implements OrderApi {

  private final Transport transport;

  public OrderApiImpl(Transport transport) {
    this.transport = transport;
  }

  @Override
  public PlaceOrderResponse placeOrder(Consumer<PlaceOrderRequest> spec) {
    // encodes the request, calls transport.request(), decodes the reply
  }

  @Override
  public Mono<PlaceOrderResponse> placeOrderAsync(Consumer<PlaceOrderRequest> spec) {
    return Mono.fromCallable(() -> placeOrder(spec));
  }
  // ...
}
```

**The impl is regenerated on every build.** Do not edit it manually.

---

## 12. The Transport interface

`Transport` is the single integration point between the generated SDK and your messaging
system. You implement it once and pass it to the generated `XxxApiImpl` constructor.

```java
public interface Transport {

  /**
   * Send a command frame and block until the reply frame arrives.
   * Used by: requestOne, fireAndForget, subscription-with-command.
   *
   * @param command the SBE-encoded frame including the message header
   * @param offset  start offset within the buffer (typically 0)
   * @param length  total frame length in bytes
   * @return the reply frame (including its message header)
   */
  DirectBuffer request(DirectBuffer command, int offset, int length);

  /**
   * Subscribe to a named topic and receive a stream of raw SBE frames.
   * Used by: subscription.
   *
   * The returned Flux may be cold (each subscriber triggers a new subscription)
   * or hot (all subscribers share the same stream) depending on your implementation.
   *
   * @param topic the topic name from the YAML descriptor
   * @return a Flux of raw SBE frames (including message headers)
   */
  Flux<DirectBuffer> subscribe(String topic);

  /**
   * Send a command frame and receive a stream of reply frames.
   * Used by: requestMany.
   *
   * Unlike subscribe(), the stream here is a direct response to this specific command.
   * The stream is typically finite — the server sends all results and then completes.
   *
   * @param command the SBE-encoded frame including the message header
   * @param offset  start offset within the buffer (typically 0)
   * @param length  total frame length in bytes
   * @return a Flux of reply frames (including message headers)
   */
  Flux<DirectBuffer> requestMany(DirectBuffer command, int offset, int length);
}
```

### Implementing Transport for your system

The Transport interface is deliberately minimal. Your implementation translates these
generic method calls into whatever your underlying messaging layer requires.

```java
public class MyTransport implements Transport {

  private final MyMessagingClient client;

  public MyTransport(MyMessagingClient client) {
    this.client = client;
  }

  @Override
  public DirectBuffer request(DirectBuffer command, int offset, int length) {
    byte[] frame = new byte[length];
    command.getBytes(offset, frame, 0, length);
    byte[] reply = client.sendAndReceive(frame);
    return new UnsafeBuffer(reply);
  }

  @Override
  public Flux<DirectBuffer> subscribe(String topic) {
    return client.subscribeToTopic(topic)
        .map(bytes -> (DirectBuffer) new UnsafeBuffer(bytes));
  }

  @Override
  public Flux<DirectBuffer> requestMany(DirectBuffer command, int offset, int length) {
    byte[] frame = new byte[length];
    command.getBytes(offset, frame, 0, length);
    return client.requestStream(frame)
        .map(bytes -> (DirectBuffer) new UnsafeBuffer(bytes));
  }
}
```

### Testing with a Transport stub

For unit tests, create an anonymous implementation that returns canned replies:

```java
private static final Transport FAKE = new Transport() {

  @Override
  public DirectBuffer request(DirectBuffer cmd, int offset, int length) {
    // Return an 8-byte buffer — enough for a message header.
    // templateId will be 0 = unknown, so the generated code throws.
    // Use assertThatThrownBy() to verify this in tests.
    return new UnsafeBuffer(new byte[8]);
  }

  @Override
  public Flux<DirectBuffer> subscribe(String topic) {
    return Flux.empty();
  }

  @Override
  public Flux<DirectBuffer> requestMany(DirectBuffer cmd, int offset, int length) {
    return Flux.empty();
  }
};
```

For integration tests, return properly-encoded SBE frames by using the same encoder
classes that the SDK uses internally:

```java
@Override
public DirectBuffer request(DirectBuffer cmd, int offset, int length) {
  MessageHeaderDecoder hdr = new MessageHeaderDecoder().wrap(cmd, 0);
  if (hdr.templateId() == PlaceOrderRequestDecoder.TEMPLATE_ID) {
    ExpandableArrayBuffer buf = new ExpandableArrayBuffer(64);
    PlaceOrderResponseEncoder enc = new PlaceOrderResponseEncoder();
    MessageHeaderEncoder hdrEnc   = new MessageHeaderEncoder();
    enc.wrapAndApplyHeader(buf, 0, hdrEnc);
    enc.orderId(1001L).clientOrderId(42L).status(OrderStatus.Active);
    int len = MessageHeaderEncoder.ENCODED_LENGTH + enc.encodedLength();
    return new UnsafeBuffer(buf, 0, len);
  }
  throw new UnsupportedOperationException("unhandled templateId: " + hdr.templateId());
}
```

---

## 13. Error handling patterns

Three patterns are available. Choose based on your coding style and context.

### Pattern 1 — synchronous try/catch

```java
try {
  PlaceOrderResponse response = api.placeOrder(req -> req
      .clientOrderId(42L)
      .instrumentId(1)
      .price(10000L)
      .quantity(100L)
      .side(Side.Buy)
      .timeInForce(TimeInForce.GoodTillCancel));
  // use response...
} catch (PlaceOrderFailed e) {
  log.error("Order rejected: code={} message={}",
      e.errorCode(), e.errorMessage());
}
```

Use this for synchronous application code where you want to handle errors inline.

### Pattern 2 — reactive Mono operators

```java
api.placeOrderAsync(req -> req.clientOrderId(42L).instrumentId(1)
                              .price(10000L).quantity(100L)
                              .side(Side.Buy).timeInForce(TimeInForce.GoodTillCancel))
    .doOnNext(response -> log.info("Order placed: {}", response.orderId()))
    .onErrorResume(PlaceOrderFailed.class, e -> {
      log.error("Order rejected: {}", e.errorMessage());
      return Mono.empty();
    })
    .subscribe();
```

Use this when you are building reactive pipelines and want to handle errors as part of
the stream rather than catching exceptions.

### Pattern 3 — error callback variants

```java
// callbackErrorWithRequest — you get both the original request and the error
api.placeOrderUnsuccessfully(
    req -> req.clientOrderId(42L).instrumentId(1)
              .price(10000L).quantity(100L)
              .side(Side.Buy).timeInForce(TimeInForce.GoodTillCancel),
    (request, error) -> log.error(
        "Order {} rejected: {}",
        request.clientOrderId(),
        error.errorMessage()));

// callbackError — you get only the error
api.cancelOrderUnsuccessfully(
    req -> req.orderId(orderId),
    error -> log.error("Cancel rejected: {}", error.errorMessage()));
```

Use callback variants when:
- You are integrating with a callback-based framework
- You need the original request object in the error handler (`callbackErrorWithRequest`)
- You want to avoid try/catch boilerplate in certain calling patterns

---

## 14. The example module — full showcase

The `example` module demonstrates every operation style in a single working project.
Reading the example is the fastest way to understand the generator.

### The SBE schema (`example-sbe`)

Located at `example-sbe/src/main/resources/sbe/order-schema.xml`.

Defines 15 messages across 5 groups:

| Template ID | Message | Used by |
|---|---|---|
| 1 | `PlaceOrderRequest` | placeOrder command |
| 2 | `PlaceOrderResponse` | placeOrder success reply |
| 3 | `PlaceOrderFailed` | placeOrder error reply |
| 4 | `CancelOrderRequest` | cancelOrder command |
| 5 | `CancelOrderResponse` | cancelOrder success reply |
| 6 | `CancelOrderFailed` | cancelOrder error reply |
| 7 | `ModifyOrderRequest` | modifyOrder command |
| 8 | `ModifyOrderResponse` | modifyOrder success reply |
| 9 | `OrderPlaced` | subscription event |
| 10 | `OrderCancelled` | subscription event |
| 11 | `OrderExecuted` | subscription event |
| 12 | `Heartbeat` | fireAndForget command |
| 13 | `GetOrderHistoryRequest` | requestMany command |
| 14 | `OrderHistoryEntry` | requestMany stream element |
| 15 | `SubscribeOrdersRequest` | subscription-with-command register message |

### The service YAML (`example/src/main/resources/order-service.yaml`)

```yaml
service: OrderApi
package: com.example.order.api

operations:

  - name: placeOrder
    style: requestOne
    command: PlaceOrderRequest
    reply:   PlaceOrderResponse
    onError: PlaceOrderFailed
    errorVariants:
      - name: placeOrderUnsuccessfully
        style: callbackErrorWithRequest

  - name: cancelOrder
    style: requestOne
    command: CancelOrderRequest
    reply:   CancelOrderResponse
    onError: CancelOrderFailed
    errorVariants:
      - name: cancelOrderUnsuccessfully
        style: callbackError

  - name: modifyOrder
    style: requestOne
    command: ModifyOrderRequest
    reply:   ModifyOrderResponse

  - name: subscribeOrderPlaced
    style: subscription
    topic: order.events
    event: OrderPlaced

  - name: subscribeOrderCancelled
    style: subscription
    topic: order.events
    event: OrderCancelled

  - name: subscribeOrderExecuted
    style: subscription
    topic: order.events
    event: OrderExecuted

  - name: sendHeartbeat
    style: fireAndForget
    command: Heartbeat

  - name: getOrderHistory
    style: requestMany
    command: GetOrderHistoryRequest
    event:   OrderHistoryEntry

  - name: subscribeOrdersByInstrument
    style: subscription
    command: SubscribeOrdersRequest
    topic:   order.events
    event:   OrderPlaced
```

### The generated interface

Running `mvn generate-sources` on the example module produces this interface exactly:

```java
public interface OrderApi {

  // requestOne — sync + async + error callback (BiConsumer variant)
  PlaceOrderResponse placeOrder(Consumer<PlaceOrderRequest> spec); // throws PlaceOrderFailed
  Mono<PlaceOrderResponse> placeOrderAsync(Consumer<PlaceOrderRequest> spec);
  void placeOrderUnsuccessfully(Consumer<PlaceOrderRequest> spec,
                                BiConsumer<PlaceOrderRequest, PlaceOrderFailed> onError);

  // requestOne — sync + async + error callback (Consumer variant)
  CancelOrderResponse cancelOrder(Consumer<CancelOrderRequest> spec); // throws CancelOrderFailed
  Mono<CancelOrderResponse> cancelOrderAsync(Consumer<CancelOrderRequest> spec);
  void cancelOrderUnsuccessfully(Consumer<CancelOrderRequest> spec,
                                 Consumer<CancelOrderFailed> onError);

  // requestOne — sync + async, no error variant
  ModifyOrderResponse modifyOrder(Consumer<ModifyOrderRequest> spec);
  Mono<ModifyOrderResponse> modifyOrderAsync(Consumer<ModifyOrderRequest> spec);

  // subscription — no command
  Flux<OrderPlaced>    subscribeOrderPlaced();
  Flux<OrderCancelled> subscribeOrderCancelled();
  Flux<OrderExecuted>  subscribeOrderExecuted();

  // fireAndForget — sync + async
  void sendHeartbeat(Consumer<Heartbeat> spec);
  Mono<Void> sendHeartbeatAsync(Consumer<Heartbeat> spec);

  // requestMany — direct reply stream
  Flux<OrderHistoryEntry> getOrderHistory(Consumer<GetOrderHistoryRequest> spec);

  // subscription — with command
  Flux<OrderPlaced> subscribeOrdersByInstrument(Consumer<SubscribeOrdersRequest> spec);
}
```

### Example test

```java
class OrderApiTest {

  private static final Transport FAKE = new Transport() {
    @Override
    public DirectBuffer request(DirectBuffer cmd, int offset, int length) {
      return new UnsafeBuffer(new byte[8]);
    }
    @Override
    public Flux<DirectBuffer> subscribe(String topic) { return Flux.empty(); }
    @Override
    public Flux<DirectBuffer> requestMany(DirectBuffer cmd, int offset, int length) {
      return Flux.empty();
    }
  };

  @Test
  void placeOrder_throwsWhenTransportReturnsUnknownFrame() {
    OrderApi api = new OrderApiImpl(FAKE);
    assertThatThrownBy(() -> api.placeOrder(req -> {}))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void sendHeartbeatAsync_returnsMono() {
    OrderApi api = new OrderApiImpl(FAKE);
    assertThat(api.sendHeartbeatAsync(req -> {})).isNotNull();
  }

  @Test
  void getOrderHistory_returnsFlux() {
    OrderApi api = new OrderApiImpl(FAKE);
    assertThat(api.getOrderHistory(req -> {})).isNotNull();
  }

  @Test
  void subscribeOrdersByInstrument_returnsFlux() {
    OrderApi api = new OrderApiImpl(FAKE);
    assertThat(api.subscribeOrdersByInstrument(req -> {})).isNotNull();
  }
}
```

---

## 15. Type mapping reference

The generator maps SBE types to Java types according to this table. These conversions are
applied automatically — you never see the SBE types in your application code.

### Primitive types

| SBE type | Java type | Notes |
|---|---|---|
| `int8` | `byte` | signed |
| `int16` | `short` | signed |
| `int32` | `int` | signed |
| `int64` | `long` | signed |
| `uint8` | `short` | widened to avoid sign issues |
| `uint16` | `int` | widened |
| `uint32` | `long` | widened |
| `uint64` | `long` | Note: may overflow for very large unsigned values |
| `char` | `char` | single character |

### Optional primitives (`presence="optional"`)

| SBE type | Java type | Notes |
|---|---|---|
| `int8` optional | `Byte` | `null` encodes as SBE null sentinel value |
| `int16` optional | `Short` | |
| `int32` optional | `Integer` | |
| `int64` optional | `Long` | |
| `uint8` optional | `Short` | |
| `uint16` optional | `Integer` | |
| `uint32` optional | `Long` | |

### Composite types

| SBE composite | Java type | Detection |
|---|---|---|
| UUID composite (has `mostSignificantBits` + `leastSignificantBits`) | `java.util.UUID` | detected by field names |
| Decimal composite (has `mantissa` + `exponent`) | `java.math.BigDecimal` | detected by field names |
| Exchange decimal (has `value` + `scale`) | `java.math.BigDecimal` | detected by field names |
| Any other composite with named sub-fields | custom POJO in API package | |

### Other SBE constructs

| SBE construct | Java type | Notes |
|---|---|---|
| `enum` | Java `enum` in API package | The enum is copied — no SBE import leaks into your code |
| Char array type | `String` | Padded with null bytes on write, trimmed on read |
| `varStringEncoding` | `String` | UTF-8 |
| Repeating group | `List<T>` + `addT(Consumer<T>)` | Inner class `T` generated in the request builder |

---

## 16. Naming conventions

### From SBE message name to Java class name

The generator uses the SBE message name **as-is** as the Java class name. No suffix
stripping occurs. This is intentional:

```
SBE message name          Java class generated
────────────────────────  ──────────────────────────────────────────
PlaceOrderRequest     →   PlaceOrderRequest.java  (request builder)
PlaceOrderResponse    →   PlaceOrderResponse.java (result POJO)
PlaceOrderFailed      →   PlaceOrderFailed.java   (exception)
OrderPlaced           →   OrderPlaced.java        (event POJO)
Heartbeat             →   Heartbeat.java          (request builder)
```

### Derived class names

| Class | Naming rule | Example |
|---|---|---|
| Request builder | SBE message name | `PlaceOrderRequest` |
| Result POJO | SBE message name | `PlaceOrderResponse` |
| Exception | SBE message name | `PlaceOrderFailed` |
| Writer mapper | `{MessageName}Mapper` | `PlaceOrderRequestMapper` |
| Reader mapper | `{MessageName}Mapper` | `PlaceOrderResponseMapper` |
| Service interface | `service` field in YAML | `OrderApi` |
| Service impl | `service` field + `Impl` | `OrderApiImpl` |
| Enum | SBE type name | `Side`, `OrderStatus` |

### Method naming by style

| Style | Sync method | Async method | Error callback |
|---|---|---|---|
| `requestOne` | `name(Consumer<Cmd>)` | `nameAsync(Consumer<Cmd>)` | `errorVariant.name(Consumer<Cmd>, callback)` |
| `requestMany` | — | `name(Consumer<Cmd>)` → `Flux` | — |
| `subscription` | — | `name()` or `name(Consumer<Cmd>)` → `Flux` | — |
| `fireAndForget` | `name(Consumer<Cmd>)` | `nameAsync(Consumer<Cmd>)` → `Mono<Void>` | — |

### Deduplication

If two operations reference the same SBE message (e.g. both use `PlaceOrderRequest` as
their command), the generator emits only one `PlaceOrderRequest.java` file. The
`notYetEmitted` guard ensures no file is written twice.

---

## 17. Best practices

### One YAML file per service

Each YAML file should describe exactly one cohesive service. Each YAML produces exactly
one interface and one impl.

```
src/main/resources/
  order-service.yaml        ✓ one service per file
  instrument-service.yaml   ✓
  account-service.yaml      ✓
```

### Choose the right style

| You want to... | Use |
|---|---|
| Send a command and get one typed reply | `requestOne` |
| Send a command and get a finite stream of replies | `requestMany` |
| Listen for live push events on a topic | `subscription` (no command) |
| Send a subscription request then receive push events | `subscription` (with command) |
| Send a command and not care about the reply | `fireAndForget` |

### Name your SBE messages consistently

The generated Java types are named after your SBE messages. Using consistent suffixes makes
the generated API self-documenting:

- Commands: `PlaceOrderRequest`, `CancelOrderRequest`
- Success replies: `PlaceOrderResponse`, `CancelOrderResponse`
- Error replies: `PlaceOrderFailed`, `CancelOrderFailed`
- Events: `OrderPlaced`, `OrderCancelled`

### Keep your Transport implementation thin

The Transport interface is intentionally minimal. Your implementation should:
- Translate method calls to your messaging system's primitives
- Handle connection management and reconnection
- Not contain any business logic

Business logic belongs in the service layer that calls the generated SDK, not in Transport.

### Use `onError` types that carry enough diagnostic information

All generated error types extend `SdkException`, which extends `RuntimeException`. Write
your SBE error messages with enough fields to diagnose the problem — an error code enum
and a description string are usually sufficient.

### Regenerate on every build — do not edit generated files

All files under `target/generated-sources/sdk/` are regenerated on every
`mvn generate-sources`. Any manual edits will be overwritten. If you need to customise
the generated API, fork the generator and modify `JavaEmitter.java`.

### Suppress checkstyle on generated sources

If your project enforces checkstyle, suppress it for the generated sources directory to
avoid false positives. See [Troubleshooting §19](#19-troubleshooting) for the exact XML.

---

## 18. What is NOT supported

The following features are not currently supported. This list is explicit so you know your
limits before you start.

| Feature | Why not supported | Workaround |
|---|---|---|
| **Bidirectional streaming** (client sends Flux, server replies with Flux) | No `requestChannel` style exists | Implement manually via separate `subscription` and `fireAndForget` operations |
| **Multiple `onError` types per operation** | Only one `onError` message per operation | Encode a discriminator field in a single error message |
| **Dynamic topics** (topic computed at runtime) | Topics are compile-time strings in the YAML | Use `subscription-with-command` and encode the effective topic in the register command |
| **`varData` in reply/event messages decodes to `String` only** | No `byte[]` access is generated | Access the raw frame via the Transport if you need byte-level access |
| **Nested repeating groups** | The type model does not handle multi-level nesting | Flatten your schema — use a group with enough fields instead of nested groups |
| **Repeating groups in reply/event messages** | Groups are supported in request builders only; reply/event messages support only flat fields | Keep reply messages flat |
| **SBE schema version negotiation** | The generator always uses `SCHEMA_VERSION` from the decoder constants | Generate a separate SDK per schema version if needed |
| **Non-Java targets** | Only Java is supported | The generator is extensible; see §20 for adding a new emitter |
| **Backpressure signals sent back to server** | The generated `Flux` uses standard Reactor backpressure, but nothing sends signals to the server | Handle at the Transport level if your messaging system supports it |

---

## 19. Troubleshooting

### "Operation 'X' references unknown command message 'Y'"

**Cause:** The message name in your YAML does not match any message in the SBE schema.

**Fix:**
1. Check spelling. Message names are case-sensitive.
2. Verify the `sbe.root.package` property matches the `package` attribute in your schema.
3. Run `mvn generate-sources -X` and look for lines like
   `SbeClassInspector: found message Y` to see which messages were actually detected.
4. If your codecs JAR is not listed in `additionalClasspathElements`, the generator will
   find no messages at all and every operation will fail.

---

### The generator runs but produces no files

**Cause:** Either no `.yaml` files were found in the specified directory, or all YAML files
were skipped because their messages were not found in the schema.

**Fix:**
1. Check that your resources directory contains `.yaml` files at the top level — the
   generator does not recurse into subdirectories.
2. Check the Maven output for `SdkGenerator: SKIP ...` lines. If every file is skipped,
   the SBE package is likely wrong or the codecs JAR is missing.

---

### Compile error: "Generated sources not found"

**Symptom:** `javac` complains that `OrderApi`, `OrderApiImpl`, etc. do not exist, even
though the generator ran successfully.

**Cause:** The `build-helper-maven-plugin` is missing or misconfigured.

**Fix:** Add the `build-helper-maven-plugin` execution to your pom (see §7).
Without it, Maven does not know the generated directory is a source root.

---

### Compile error: "XxxApiImpl does not implement abstract method requestMany()"

**Symptom:** After upgrading `scalecube-sbe-weaver-sdk`, existing Transport stub
implementations fail to compile because a new method was added to the interface.

**Fix:** Add the missing method to all Transport implementations and stubs:

```java
@Override
public Flux<DirectBuffer> requestMany(DirectBuffer cmd, int offset, int length) {
  return Flux.empty(); // stub — replace with real implementation
}
```

---

### Generated code fails checkstyle in your project

**Symptom:** Your project's checkstyle configuration flags generated files for line
length, import order, etc.

**Fix:** Add a checkstyle suppression for the generated sources directory:

```xml
<!-- checkstyle-suppressions.xml -->
<suppressions>
  <suppress checks=".*"
            files="[\\/]generated-sources[\\/]sdk[\\/]"/>
</suppressions>
```

Reference it in your checkstyle plugin configuration using
`${maven.multiModuleProjectDirectory}/checkstyle-suppressions.xml`
(not `${project.basedir}/` — the multi-module path is needed for sub-modules to find it).

---

### `IllegalStateException: Unexpected templateId: 0` in tests

**Cause:** Your test Transport stub's `request()` method returns an 8-byte zero buffer.
The generated code reads the `templateId` from the message header and gets `0`, which
does not match any known reply message.

**This is expected behaviour** when using a minimal stub. Use `assertThatThrownBy()` to
verify the exception, or return a properly-encoded reply frame from your stub (see §12).

---

### `NullPointerException` during generation

**Cause:** Usually a YAML field that is required for the chosen style is missing.

**Fix:**
- `requestOne` requires both `command` and `reply`.
- `requestMany` requires both `command` and `event`.
- `subscription` requires both `topic` and `event` (and optionally `command`).
- `fireAndForget` requires only `command`.

Check that the YAML operation has all required fields for its style.

---

## 20. Contributing — adding a new operation style

If you need a pattern that is not covered by the four built-in styles, you can add one.
Here is exactly what to change, using `requestMany` as a reference implementation.

### The five touch points

**1. `OperationDescriptor.java`** — add an `isXxx()` predicate:

```java
public boolean isMyNewStyle() {
  return "myNewStyle".equals(style);
}
```

**2. `SdkGenerator.validate()`** — add a validation branch:

```java
} else if (op.isMyNewStyle()) {
  requireMessage(schema, op.command, "command", op.name);
  requireMessage(schema, op.event,   "event",   op.name);
```

**3. `JavaEmitter.emit()`** — add the file-emission branch:

```java
} else if (op.isMyNewStyle()) {
  SbeMessage cmd   = schema.messages.get(op.command);
  SbeMessage event = schema.messages.get(op.event);
  emitRequestBuilder(pkgDir, cmd);
  emitWriterMapper(pkgDir, cmd);
  emitResultPojo(pkgDir, event);
  emitReplyReaderMapper(pkgDir, event);
```

**4. `emitServiceInterface()`** — add the interface method signature:

```java
} else if (op.isMyNewStyle()) {
  String builderClass = TypeMapper.publicName(schema.messages.get(op.command).name);
  String eventClass   = TypeMapper.publicName(schema.messages.get(op.event).name);
  sb.append("  Flux<").append(eventClass).append("> ").append(op.name)
    .append("(Consumer<").append(builderClass).append("> spec);\n\n");
```

**5. `emitServiceImpl()`** — add import collection and method dispatch:

```java
// In the import loop:
} else if (op.isMyNewStyle()) {
  // append import statements for encoder and decoder

// In the method dispatch loop:
} else if (op.isMyNewStyle()) {
  emitMyNewStyleMethod(sb, op);
```

Then write the `emitMyNewStyleMethod(StringBuilder sb, OperationDescriptor op)` private
method following the same pattern as `emitRequestManyMethod`.

### Adding an example

Add the new style to `example/src/main/resources/order-service.yaml` and the corresponding
SBE messages to `example-sbe/src/main/resources/sbe/order-schema.xml`, then add a test to
`example/src/test/java/com/example/order/api/OrderApiTest.java`.

### Adding a generator test

Add a test YAML to `generator/src/test/resources/` and extend `SdkGeneratorTest.java` to
verify the new files are emitted and the interface signatures look correct.

---

## License

[MIT](LICENSE) © scalecube

---

*Generated code is your code. No runtime licence restrictions apply to files produced by scalecube-sbe-weaver.*
