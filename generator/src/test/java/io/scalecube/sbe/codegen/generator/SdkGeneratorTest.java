package io.scalecube.sbe.codegen.generator;

import io.scalecube.sbe.codegen.generator.model.*;
import io.scalecube.sbe.codegen.generator.parser.SbeClassInspector;
import io.scalecube.sbe.codegen.generator.parser.ServiceDescriptorParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SdkGeneratorTest {

  private static final String SBE_PKG = "com.example.exchange.sbe";

  private static Path resource(String name) throws Exception {
    URL url = SdkGeneratorTest.class.getClassLoader().getResource(name);
    assertThat(url).as("test resource %s must exist", name).isNotNull();
    return Path.of(url.toURI());
  }

  @Test
  void classInspectorReadsMessages() throws Exception {
    SbeSchema schema = new SbeClassInspector().inspect(SBE_PKG);

    assertThat(schema.packageName).isEqualTo(SBE_PKG);
    assertThat(schema.messages).containsKeys(
        "CreateCalendarCommand", "CalendarCreatedEvent",
        "CreateCalendarFailedEvent", "UpdateCalendarCommand", "CalendarUpdatedEvent");

    SbeMessage cmd = schema.messages.get("CreateCalendarCommand");
    assertThat(cmd.fields).extracting(f -> f.name)
        .containsExactly("projectId", "timeZoneOffsetMinutes", "name", "fee");
    assertThat(cmd.groups).extracting(g -> g.name).containsExactly("holidays");
    assertThat(cmd.varData).extracting(d -> d.name).containsExactly("userId");

    SbeField tz = cmd.fields.stream()
        .filter(f -> f.name.equals("timeZoneOffsetMinutes")).findFirst().orElseThrow();
    assertThat(tz.optional).isTrue();
  }

  @Test
  void classInspectorReadsTypes() throws Exception {
    SbeSchema schema = new SbeClassInspector().inspect(SBE_PKG);

    SbeTypeInfo charArray = schema.types.get("charArray");
    assertThat(charArray.kind).isEqualTo(SbeTypeKind.CHAR_ARRAY);

    SbeTypeInfo decimal = schema.types.get("Decimal");
    assertThat(decimal.kind).isEqualTo(SbeTypeKind.COMPOSITE);

    SbeTypeInfo errCode = schema.types.get("ErrorCode");
    assertThat(errCode.kind).isEqualTo(SbeTypeKind.ENUM);
    assertThat(errCode.enumValues).extracting(v -> v.name)
        .containsExactly("OK", "INVALID_PROJECT", "NOT_FOUND", "INTERNAL");
  }

  @Test
  void serviceParserReadsOperations() throws Exception {
    ServiceDescriptor svc = new ServiceDescriptorParser().parse(resource("calendar-service.yaml").toString());

    assertThat(svc.serviceName).isEqualTo("CalendarApi");
    assertThat(svc.packageName).isEqualTo("com.example.exchange.api");
    assertThat(svc.operations).hasSize(5);

    OperationDescriptor create = svc.operations.get(0);
    assertThat(create.name).isEqualTo("createCalendar");
    assertThat(create.command).isEqualTo("CreateCalendarCommand");
    assertThat(create.reply).isEqualTo("CalendarCreatedEvent");
    assertThat(create.onError).isEqualTo("CreateCalendarFailedEvent");
    assertThat(create.isRequestOne()).isTrue();
    assertThat(create.isFireAndForget()).isFalse();
    assertThat(create.errorVariants).hasSize(2);
    assertThat(create.errorVariants.get(0).name).isEqualTo("createCalendarUnsuccessfully");
    assertThat(create.errorVariants.get(0).isWithRequest()).isFalse();
    assertThat(create.errorVariants.get(1).name).isEqualTo("createCalendarUnsuccessfullyWithContext");
    assertThat(create.errorVariants.get(1).isWithRequest()).isTrue();

    OperationDescriptor update = svc.operations.get(1);
    assertThat(update.isFireAndForget()).isTrue();
    assertThat(update.onError).isNull();

    OperationDescriptor endOfDay = svc.operations.get(2);
    assertThat(endOfDay.name).isEqualTo("runCalendarEndOfDay");
    assertThat(endOfDay.isFireAndForget()).isTrue();

    OperationDescriptor sub1 = svc.operations.get(3);
    assertThat(sub1.isSubscription()).isTrue();
    assertThat(sub1.topic).isEqualTo("calendar.created");
    assertThat(sub1.event).isEqualTo("CalendarCreatedEvent");

    OperationDescriptor sub2 = svc.operations.get(4);
    assertThat(sub2.isSubscription()).isTrue();
    assertThat(sub2.topic).isEqualTo("calendar.updated");
    assertThat(sub2.event).isEqualTo("CalendarUpdatedEvent");
  }

  @Test
  void generatorRunsCleanlyOnValidInputs(@TempDir Path tmp) throws Exception {
    new SdkGenerator().run(SBE_PKG, resource("calendar-service.yaml").toString(), tmp.toString());
  }

  @Test
  void generatorEmitsExpectedFiles(@TempDir Path tmp) throws Exception {
    new SdkGenerator().run(SBE_PKG, resource("calendar-service.yaml").toString(), tmp.toString());

    Path apiDir = tmp.resolve("com/example/exchange/api");
    assertThat(apiDir.resolve("CalendarApi.java")).exists();
    assertThat(apiDir.resolve("CalendarApiImpl.java")).exists();
    assertThat(apiDir.resolve("CreateCalendar.java")).exists();
    assertThat(apiDir.resolve("CalendarCreated.java")).exists();
    assertThat(apiDir.resolve("CreateCalendarFailed.java")).exists();
    assertThat(apiDir.resolve("UpdateCalendar.java")).exists();
    assertThat(apiDir.resolve("CalendarUpdated.java")).exists();
    assertThat(apiDir.resolve("RunCalendarEndOfDay.java")).exists();
    assertThat(apiDir.resolve("CalendarEndOfDay.java")).exists();
    assertThat(apiDir.resolve("ErrorCode.java")).exists();
  }
}
