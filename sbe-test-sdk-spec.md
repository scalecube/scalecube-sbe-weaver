# Spec: SBE-backed test SDK generator (POC)

## 1. Goal

Build a **code generator** that turns an SBE message schema plus a small service
descriptor (YAML) into an **ease-of-use Java test SDK**. Test code works only with
plain Java request/result objects and never sees an SBE encoder, decoder, or buffer.
A command's success reply is **returned**; its failure reply is **thrown** as a typed
exception.

This is a POC for **integration test authoring**. Latency and allocation do not matter.
Optimize entirely for how the call site reads.

### What the client sees (the whole point)

```java
CalendarApi api = new CalendarApiImpl(new StubTransport());

// success → returned
CalendarCreated created = api.createCalendar(c -> c
    .projectId(7)
    .name("NYSE")
    .fee(new BigDecimal("0.25"))
    .addHoliday(h -> h.epochDay(20100))
    .userId("alice"));

// failure → thrown, with the failure event's fields on the exception
assertThatThrownBy(() -> api.createCalendar(c -> c.projectId(-1).name("X").userId("bob")))
    .isInstanceOf(CreateCalendarFailed.class)
    .satisfies(e -> assertThat(((CreateCalendarFailed) e).errorCode())
        .isEqualTo(ErrorCode.INVALID_PROJECT));
```

No `…Encoder`, `…Decoder`, `MutableDirectBuffer`, or SBE import appears in any public type.

## 2. Non-goals (do NOT build these)

- No real network transport. A synchronous in-memory `StubTransport` with canned
  replies is the only transport in the POC.
- No performance work: no buffer pooling, no flyweight reuse, no zero-copy. Allocate freely.
- No support for the full production schema. Use only the sample schema in §4.
- No reflective dynamic proxy. Generate a concrete `CalendarApiImpl` with real method
  bodies — it is simpler to read and debug. `@SdkCall` is an optional documentation
  marker, not load-bearing.
- No correlation-id matching. The stub returns the reply directly (1 request : 1 reply).
  Note in a comment where a real transport would match by `templateId` + correlation id.

## 3. Architecture

Two inputs, three kinds of generated artifact, one tiny runtime.

```
exchange-schema.xml ──► sbe-tool ──────────────► *Encoder / *Decoder   (internal, generated)
                   └──► SdkGenerator ──┐
calendar-service.yaml ─────────────────┴───────► request builders, result POJOs,
                                                  failure exceptions, mappers,
                                                  CalendarApi + CalendarApiImpl   (generated)
runtime (hand-written): AsyncReply, Transport, SdkException, StubTransport
```

- **sbe-tool** (Real Logic) generates the SBE codecs from the schema. Do not reimplement it.
- **`SdkGenerator`** (the thing this POC builds) reads the same schema XML + the service
  YAML and emits the public types, mappers, and API. For the POC, re-parse the (small,
  self-contained) XML with a DOM parser to get messages, fields, groups, data, and types.
  (Production upgrade, out of scope: consume sbe-tool's `.sbeir` via `IrDecoder` instead.)

## 4. Sample input A — the SBE schema

Self-contained: all types are inline, no `xi:include`. Save as
`src/main/resources/exchange-schema.xml`.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<sbe:messageSchema xmlns:sbe="http://fixprotocol.io/2016/sbe"
                   package="com.example.exchange.sbe"
                   id="1" version="0" byteOrder="littleEndian">
  <types>
    <composite name="messageHeader">
      <type name="blockLength" primitiveType="uint16"/>
      <type name="templateId"  primitiveType="uint16"/>
      <type name="schemaId"    primitiveType="uint16"/>
      <type name="version"     primitiveType="uint16"/>
    </composite>
    <composite name="groupSizeEncoding">
      <type name="blockLength" primitiveType="uint16"/>
      <type name="numInGroup"  primitiveType="uint16"/>
    </composite>
    <composite name="varStringEncoding">
      <type name="length"  primitiveType="uint32" maxValue="1073741824"/>
      <type name="varData" primitiveType="uint8" length="0" characterEncoding="UTF-8"/>
    </composite>
    <composite name="Decimal" description="value = mantissa * 10^exponent">
      <type name="mantissa" primitiveType="int64"/>
      <type name="exponent" primitiveType="int8"/>
    </composite>
    <type name="CalendarName" primitiveType="char" length="16" characterEncoding="US-ASCII"/>
    <enum name="ErrorCode" encodingType="uint8">
      <validValue name="OK">0</validValue>
      <validValue name="INVALID_PROJECT">1</validValue>
      <validValue name="NOT_FOUND">2</validValue>
      <validValue name="INTERNAL">3</validValue>
    </enum>
  </types>

  <!-- command -->
  <sbe:message name="CreateCalendarCommand" id="1">
    <field name="projectId"            id="1" type="int32"/>
    <field name="timeZoneOffsetMinutes" id="2" type="int16" presence="optional"/>
    <field name="name"                 id="3" type="CalendarName"/>
    <field name="fee"                  id="4" type="Decimal"/>
    <group name="holidays" id="10" dimensionType="groupSizeEncoding">
      <field name="epochDay" id="11" type="int32"/>
    </group>
    <data name="userId" id="20" type="varStringEncoding"/>
  </sbe:message>

  <!-- success reply -->
  <sbe:message name="CalendarCreatedEvent" id="2">
    <field name="calendarId" id="1" type="int32"/>
    <field name="projectId"  id="2" type="int32"/>
    <field name="name"       id="3" type="CalendarName"/>
  </sbe:message>

  <!-- failure reply -->
  <sbe:message name="CreateCalendarFailedEvent" id="3">
    <field name="errorCode" id="1" type="ErrorCode"/>
    <data  name="errorMessage" id="10" type="varStringEncoding"/>
  </sbe:message>

  <!-- fire-and-forget command + reply -->
  <sbe:message name="UpdateCalendarCommand" id="4">
    <field name="calendarId" id="1" type="int32"/>
    <field name="name"       id="2" type="CalendarName"/>
  </sbe:message>
  <sbe:message name="CalendarUpdatedEvent" id="5">
    <field name="calendarId" id="1" type="int32"/>
  </sbe:message>
</sbe:messageSchema>
```

## 5. Sample input B — the service DSL

Save as `src/main/resources/calendar-service.yaml`.

```yaml
service: CalendarApi
package: com.example.exchange.api

operations:
  - name: createCalendar          # optional; default = decapitalize(strip "Command")
    command: CreateCalendarCommand
    reply:   CalendarCreatedEvent
    onError: CreateCalendarFailedEvent
    style:   blockingAndAsync      # generates sync (throws onError) + *Async

  - name: updateCalendar
    command: UpdateCalendarCommand
    reply:   CalendarUpdatedEvent
    style:   fireAndForget         # generates a single void method
```

### DSL rules

- `style` is one of `blockingAndAsync` or `fireAndForget`.
- `name` is optional; default it to `decapitalize(command without trailing "Command")`.
  Require it explicitly only if a command is reused across operations.
- `onError` is optional and only meaningful with `blockingAndAsync`.
- The generator MUST validate that every `command`, `reply`, and `onError` names a real
  message in the schema, and fail generation with a clear message otherwise.

## 6. Naming rules (schema name → public name)

- Strip a trailing `Command` or `Event` to form public names.
  - `CreateCalendarCommand` → request builder **`CreateCalendar`**
  - `CalendarCreatedEvent` → result **`CalendarCreated`**
  - `CreateCalendarFailedEvent` → exception **`CreateCalendarFailed`**
- Internal SBE codecs keep their generated names (`CreateCalendarCommandEncoder`, etc.)
  and stay package-private to the SDK.

## 7. Type mapping (SBE → public Java)

| SBE construct                         | Public Java type                     | Notes |
|---|---|---|
| `int8/16/32/64`, `uint8/16/32`        | `byte/short/int/long` (widen unsigned) | |
| `char` array (`CalendarName`)         | `String`                             | trim trailing NULs on read; pad/truncate on write |
| `enum`                                | generated Java `enum` (`ErrorCode`)  | reuse sbe-tool's enum if convenient |
| `Decimal` composite (mantissa+exp)    | `java.math.BigDecimal`               | read `BigDecimal.valueOf(mantissa, -exponent)`; write `mantissa=unscaledValue`, `exponent=-scale` |
| repeating group (`holidays`)          | `List<Holiday>` + `addHoliday(Consumer<Holiday>)` builder | |
| var-data `varStringEncoding`          | `String`                             | |
| `presence="optional"` primitive       | boxed nullable (`Short`, `Integer`…) | `null` ⇄ SBE null value; on write, skip if null |

The two error-prone spots that deserve their own unit tests: the `Decimal`⇄`BigDecimal`
conversion, and optional-field null handling.

## 8. Generated artifacts (shapes to produce)

Per command message: a **request builder** (public, mutable, fluent, no SBE imports) and a
**writer mapper** (package-private). Per reply message: a **result POJO** (public, immutable
getters) and a **reader mapper**. Per `onError` message: a **`SdkException` subclass** and a
mapper that builds it from the decoder. Per service: the **interface** and a concrete **impl**.

Illustrative target for `createCalendar` (Claude Code generates equivalents for all):

```java
// ---- public request builder ----
public final class CreateCalendar {
  int projectId;
  Short timeZoneOffsetMinutes;          // optional → nullable
  String name;
  BigDecimal fee;
  final List<Holiday> holidays = new ArrayList<>();
  String userId;

  public CreateCalendar projectId(int v)             { this.projectId = v; return this; }
  public CreateCalendar timeZoneOffsetMinutes(int v) { this.timeZoneOffsetMinutes = (short) v; return this; }
  public CreateCalendar name(String v)               { this.name = v; return this; }
  public CreateCalendar fee(BigDecimal v)            { this.fee = v; return this; }
  public CreateCalendar addHoliday(Consumer<Holiday> h) { Holiday g = new Holiday(); h.accept(g); holidays.add(g); return this; }
  public CreateCalendar userId(String v)             { this.userId = v; return this; }

  public static final class Holiday {
    int epochDay;
    public Holiday epochDay(int v) { this.epochDay = v; return this; }
  }
}

// ---- public immutable result ----
public final class CalendarCreated {
  private final int calendarId, projectId;
  private final String name;
  CalendarCreated(int calendarId, int projectId, String name) { /* ... */ }
  public int calendarId() { return calendarId; }
  public int projectId()  { return projectId; }
  public String name()    { return name; }
}

// ---- public failure exception ----
public final class CreateCalendarFailed extends SdkException {
  private final ErrorCode errorCode;
  private final String errorMessage;
  CreateCalendarFailed(ErrorCode c, String m) { super(c + ": " + m); this.errorCode = c; this.errorMessage = m; }
  public ErrorCode errorCode()  { return errorCode; }
  public String errorMessageText() { return errorMessage; }
}

// ---- internal writer mapper (package-private) ----
final class CreateCalendarMapper {
  static void write(CreateCalendar p, CreateCalendarCommandEncoder enc) {
    enc.projectId(p.projectId);
    enc.timeZoneOffsetMinutes(p.timeZoneOffsetMinutes == null
        ? CreateCalendarCommandEncoder.timeZoneOffsetMinutesNullValue()
        : p.timeZoneOffsetMinutes);
    enc.name(p.name);                                  // sbe-tool generates String setter for char arrays
    enc.fee().mantissa(p.fee.unscaledValue().longValueExact()).exponent((byte) -p.fee.scale());
    var hg = enc.holidaysCount(p.holidays.size());
    for (var h : p.holidays) hg.next().epochDay(h.epochDay);
    enc.userId(p.userId);                              // var-data written last
  }
}

// ---- interface + impl ----
public interface CalendarApi {
  CalendarCreated createCalendar(Consumer<CreateCalendar> spec);          // throws CreateCalendarFailed
  AsyncReply<CalendarCreated> createCalendarAsync(Consumer<CreateCalendar> spec);
  void updateCalendar(Consumer<UpdateCalendar> spec);
}
```

The generated `createCalendar` impl body, in order: new the builder → run the client
`consumer` → wrap a fresh buffer with `wrapAndApplyHeader` → run the writer mapper → call
`transport.request(...)` → peek the reply `templateId` → if it equals the reply decoder's
`TEMPLATE_ID`, run the reader mapper and return the result; if it equals the `onError`
decoder's `TEMPLATE_ID`, build and throw the exception; else throw `IllegalStateException`.
The `blockingAndAsync` style also generates `createCalendarAsync` returning
`AsyncReply.completed(...)` (sync transport), with the sync method delegating via `.block()`.
`fireAndForget` generates a single void method that sends and ignores the reply.

## 9. Runtime (hand-written, ~4 small files)

```java
public interface Transport {
  // returns the reply buffer; blocking. For the POC the reply is produced synchronously.
  org.agrona.DirectBuffer request(org.agrona.DirectBuffer command, int offset, int length);
}

public final class AsyncReply<T> {
  private final java.util.concurrent.CompletableFuture<T> f;
  private AsyncReply(java.util.concurrent.CompletableFuture<T> f) { this.f = f; }
  public static <T> AsyncReply<T> completed(T v) { return new AsyncReply<>(java.util.concurrent.CompletableFuture.completedFuture(v)); }
  public T block() {
    try { return f.join(); }
    catch (java.util.concurrent.CompletionException e) {
      if (e.getCause() instanceof RuntimeException re) throw re;   // re-throw SdkException unwrapped
      throw e;
    }
  }
}

public abstract class SdkException extends RuntimeException {
  protected SdkException(String message) { super(message); }
}
```

## 10. Sample data — `StubTransport`

This is the canned "exchange" that makes the tests runnable. It reads the command's
`templateId`, decodes the command, and writes a reply per simple rules:

- `CreateCalendarCommand`: if `projectId > 0` → `CalendarCreatedEvent` with
  `calendarId = 1000 + projectId`, echo `projectId` and `name`. Otherwise →
  `CreateCalendarFailedEvent` with `errorCode = INVALID_PROJECT`,
  `errorMessage = "projectId must be positive"`.
- `UpdateCalendarCommand`: → `CalendarUpdatedEvent` echoing `calendarId`.

Implement it with the generated SBE encoders/decoders directly (it is internal, so using
codecs here is fine). Allocate a fresh `ExpandableArrayBuffer`/`UnsafeBuffer` per reply.

## 11. Acceptance criteria

A JUnit 5 test that compiles against only public types and passes:

```java
class CalendarApiTest {
  private final CalendarApi api = new CalendarApiImpl(new StubTransport());

  @Test void createsCalendar() {
    CalendarCreated r = api.createCalendar(c -> c
        .projectId(7).name("NYSE").fee(new BigDecimal("0.25"))
        .addHoliday(h -> h.epochDay(20100))
        .userId("alice"));
    assertThat(r.calendarId()).isEqualTo(1007);
    assertThat(r.projectId()).isEqualTo(7);
    assertThat(r.name()).isEqualTo("NYSE");
  }

  @Test void failureBecomesException() {
    assertThatThrownBy(() -> api.createCalendar(c -> c.projectId(-1).name("X").userId("bob")))
        .isInstanceOf(CreateCalendarFailed.class)
        .satisfies(e -> assertThat(((CreateCalendarFailed) e).errorCode())
            .isEqualTo(ErrorCode.INVALID_PROJECT));
  }

  @Test void asyncBlocksToSameResult() {
    assertThat(api.createCalendarAsync(c -> c.projectId(3).name("LSE").userId("x")).block().calendarId())
        .isEqualTo(1003);
  }

  @Test void updateIsFireAndForget() {
    api.updateCalendar(c -> c.calendarId(1007).name("NASDAQ"));   // returns void, no throw
  }
}
```

Also include a round-trip unit test of the `Decimal`⇄`BigDecimal` mapper
(e.g. `new BigDecimal("0.25")` → mantissa 25, exponent -2 → back to `0.25`).

## 12. Build & layout

Java 17, Gradle. Two source sets / modules to avoid a build cycle (the generator must
compile and run before the generated SDK sources are compiled):

```
:generator   — SdkGenerator + DOM parsing + YAML (snakeyaml). Reads xml+yaml, writes .java.
:sdk         — runtime (Transport, AsyncReply, SdkException, StubTransport)
               + sbe-tool-generated codecs + SdkGenerator-generated sources + tests.
```

Dependencies: `uk.co.real-logic:sbe-all` (codecs), `org.agrona:agrona` (buffers),
`org.yaml:snakeyaml` (DSL), `org.junit.jupiter:junit-jupiter` + `org.assertj:assertj-core`
(tests).

Build ordering for `:sdk`:
1. Run sbe-tool (`uk.co.real_logic.sbe.SbeTool`, `-Dsbe.output.dir=...`) on
   `exchange-schema.xml` → SBE codecs.
2. Run `:generator` `SdkGenerator` with `(exchange-schema.xml, calendar-service.yaml, outputDir)`
   → public types, mappers, API.
3. Compile codecs + generated SDK + runtime + tests; run tests.

## 13. Implementation milestones

1. Runtime classes + `StubTransport` reading/writing the sample messages by hand. Prove the
   stub round-trips a hand-built command to the right reply.
2. `SdkGenerator`: parse the schema (messages, fields, groups, data, types) and the YAML.
3. Emit request builders + result POJOs + failure exceptions (types only, no mappers).
4. Emit mappers (this is the real work — groups, var-data, `Decimal`, optional).
5. Emit `CalendarApi` + `CalendarApiImpl` wiring builder → mapper → transport → reply/throw.
6. Make the §11 test pass.

## 14. Stretch (only if time remains)

- Consume sbe-tool's `.sbeir` via `IrDecoder` instead of re-parsing XML (the production path).
- Generate the SDK with the full production schema once its `sbe-common-types.xml` is available.
- Add a `callback` style and/or a `Result<Ok, Err>` return option alongside throw-on-failure.
- Record/replay transport: capture real exchange replies into fixtures for deterministic tests.
