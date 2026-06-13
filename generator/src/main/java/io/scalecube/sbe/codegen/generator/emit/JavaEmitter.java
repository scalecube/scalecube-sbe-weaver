package io.scalecube.sbe.codegen.generator.emit;

import io.scalecube.sbe.codegen.generator.model.ErrorVariantDescriptor;
import io.scalecube.sbe.codegen.generator.model.OperationDescriptor;
import io.scalecube.sbe.codegen.generator.model.SbeEnumValue;
import io.scalecube.sbe.codegen.generator.model.SbeField;
import io.scalecube.sbe.codegen.generator.model.SbeGroup;
import io.scalecube.sbe.codegen.generator.model.SbeMessage;
import io.scalecube.sbe.codegen.generator.model.SbeSchema;
import io.scalecube.sbe.codegen.generator.model.SbeTypeInfo;
import io.scalecube.sbe.codegen.generator.model.SbeTypeKind;
import io.scalecube.sbe.codegen.generator.model.SbeVarData;
import io.scalecube.sbe.codegen.generator.model.ServiceDescriptor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Emits all Java source files for one service descriptor against one SBE schema.
 *
 * <p>Per {@code requestOne} operation:
 *   request builder, result POJO, failure exception (if onError), writer mapper,
 *   reply reader mapper, error reader mapper (if onError)
 * Per {@code requestMany} operation:
 *   request builder, writer mapper, event POJO, event reader mapper
 * Per {@code subscription} operation:
 *   event POJO (shared with reply POJOs if already emitted), event reader mapper
 * Per {@code fireAndForget} operation:
 *   request builder, writer mapper
 * Plus: service interface, service impl, enum copies
 */
public class JavaEmitter {

  private final SbeSchema schema;
  private final ServiceDescriptor service;
  private final Path outputRoot;

  public JavaEmitter(SbeSchema schema, ServiceDescriptor service, Path outputRoot) {
    this.schema = schema;
    this.service = service;
    this.outputRoot = outputRoot;
  }

  public void emit() throws IOException {
    Path pkgDir = outputRoot;
    for (String part : service.packageName.split("\\.")) {
      pkgDir = pkgDir.resolve(part);
    }
    Files.createDirectories(pkgDir);

    emitEnums(pkgDir);
    emitCompositePojos(pkgDir);

    for (OperationDescriptor op : service.operations) {
      if (op.isSubscription()) {
        SbeMessage event = schema.messages.get(op.event);
        emitResultPojo(pkgDir, event);
        emitReplyReaderMapper(pkgDir, event);
        if (op.command != null) {
          SbeMessage cmd = schema.messages.get(op.command);
          emitRequestBuilder(pkgDir, cmd);
          emitWriterMapper(pkgDir, cmd);
        }
      } else if (op.isFireAndForget()) {
        SbeMessage cmd = schema.messages.get(op.command);
        emitRequestBuilder(pkgDir, cmd);
        emitWriterMapper(pkgDir, cmd);
        if (op.reply != null) {
          SbeMessage reply = schema.messages.get(op.reply);
          emitResultPojo(pkgDir, reply);
          emitReplyReaderMapper(pkgDir, reply);
        }
      } else if (op.isRequestMany()) {
        SbeMessage cmd   = schema.messages.get(op.command);
        SbeMessage event = schema.messages.get(op.event);
        emitRequestBuilder(pkgDir, cmd);
        emitWriterMapper(pkgDir, cmd);
        emitResultPojo(pkgDir, event);
        emitReplyReaderMapper(pkgDir, event);
      } else {
        SbeMessage cmd   = schema.messages.get(op.command);
        SbeMessage reply = schema.messages.get(op.reply);

        emitRequestBuilder(pkgDir, cmd);
        emitResultPojo(pkgDir, reply);
        emitWriterMapper(pkgDir, cmd);
        emitReplyReaderMapper(pkgDir, reply);

        if (op.onError != null) {
          SbeMessage err = schema.messages.get(op.onError);
          emitFailureException(pkgDir, err);
          emitErrorReaderMapper(pkgDir, err);
        }
      }
    }

    emitServiceInterface(pkgDir);
    emitServiceImpl(pkgDir);
  }

  // ── already-emitted guard ─────────────────────────────────────────────────

  private final java.util.Set<String> emitted = new java.util.HashSet<>();

  private boolean notYetEmitted(String className) {
    return emitted.add(className);
  }

  // ── Enums ─────────────────────────────────────────────────────────────────

  private void emitEnums(Path pkgDir) throws IOException {
    for (SbeTypeInfo type : schema.types.values()) {
      if (type.kind != SbeTypeKind.ENUM) {
        continue;
      }
      if (!isEnumReferencedInPublicTypes(type.name)) {
        continue;
      }
      if (!notYetEmitted(type.name)) {
        continue;
      }

      StringBuilder sb = new StringBuilder();
      sb.append("package ").append(service.packageName).append(";\n\n");
      sb.append("public enum ").append(type.name).append(" {\n");
      for (SbeEnumValue v : type.enumValues) {
        sb.append("  ").append(v.name).append(",\n");
      }
      sb.append("}\n");
      write(pkgDir, type.name + ".java", sb.toString());
    }
  }

  private boolean isEnumReferencedInPublicTypes(String enumName) {
    for (OperationDescriptor op : service.operations) {
      if (op.command != null) {
        SbeMessage cmd = schema.messages.get(op.command);
        if (cmd != null) {
          for (SbeField f : cmd.fields) {
            if (enumName.equals(f.typeName)) {
              return true;
            }
          }
          for (SbeGroup g : cmd.groups) {
            for (SbeField gf : g.fields) {
              if (enumName.equals(gf.typeName)) {
                return true;
              }
            }
          }
        }
      }
      if (op.onError != null) {
        for (SbeField f : schema.messages.get(op.onError).fields) {
          if (enumName.equals(f.typeName)) {
            return true;
          }
        }
      }
      if (op.reply != null) {
        for (SbeField f : schema.messages.get(op.reply).fields) {
          if (enumName.equals(f.typeName)) {
            return true;
          }
        }
      }
      if (op.event != null) {
        for (SbeField f : schema.messages.get(op.event).fields) {
          if (enumName.equals(f.typeName)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  // ── Request builder ───────────────────────────────────────────────────────

  private void emitRequestBuilder(Path pkgDir, SbeMessage cmd) throws IOException {
    String className = TypeMapper.publicName(cmd.name);
    if (!notYetEmitted(className)) {
      return;
    }

    StringBuilder sb = new StringBuilder();
    sb.append("package ").append(service.packageName).append(";\n\n");
    sb.append("import java.math.BigDecimal;\n");
    sb.append("import java.util.ArrayList;\n");
    sb.append("import java.util.List;\n");
    sb.append("import java.util.UUID;\n");
    sb.append("import java.util.function.Consumer;\n\n");
    sb.append("public final class ").append(className).append(" {\n\n");

    for (SbeField f : cmd.fields) {
      sb.append("  ").append(TypeMapper.publicJavaType(f, schema)).append(" ").append(f.name).append(";\n");
    }
    for (SbeGroup g : cmd.groups) {
      String inner = innerClassName(g);
      sb.append("  final List<").append(inner).append("> ").append(g.name).append(" = new ArrayList<>();\n");
    }
    for (SbeVarData d : cmd.varData) {
      sb.append("  String ").append(d.name).append(";\n");
    }
    sb.append("\n");

    for (SbeField f : cmd.fields) {
      String jt = TypeMapper.publicJavaType(f, schema);
      sb.append("  public ").append(className).append(" ").append(f.name)
        .append("(").append(jt).append(" v) { this.").append(f.name).append(" = v; return this; }\n");
    }
    for (SbeGroup g : cmd.groups) {
      String inner = innerClassName(g);
      sb.append("  public ").append(className).append(" add").append(TypeMapper.capitalize(inner))
        .append("(Consumer<").append(inner).append("> spec) { ")
        .append(inner).append(" g = new ").append(inner).append("(); spec.accept(g); ")
        .append(g.name).append(".add(g); return this; }\n");
    }
    for (SbeVarData d : cmd.varData) {
      sb.append("  public ").append(className).append(" ").append(d.name)
        .append("(String v) { this.").append(d.name).append(" = v; return this; }\n");
    }

    for (SbeGroup g : cmd.groups) {
      String inner = innerClassName(g);
      sb.append("\n  public static final class ").append(inner).append(" {\n");
      for (SbeField f : g.fields) {
        String jt = TypeMapper.publicJavaType(f, schema);
        sb.append("    ").append(jt).append(" ").append(f.name).append(";\n");
        sb.append("    public ").append(inner).append(" ").append(f.name)
          .append("(").append(jt).append(" v) { this.").append(f.name).append(" = v; return this; }\n");
      }
      sb.append("  }\n");
    }

    sb.append("}\n");
    write(pkgDir, className + ".java", sb.toString());
  }

  private static String innerClassName(SbeGroup g) {
    String base = g.name.endsWith("s") ? g.name.substring(0, g.name.length() - 1) : g.name;
    return TypeMapper.capitalize(base);
  }

  // ── Result POJO ───────────────────────────────────────────────────────────

  private void emitResultPojo(Path pkgDir, SbeMessage msg) throws IOException {
    String className = TypeMapper.publicName(msg.name);
    if (!notYetEmitted(className)) {
      return;
    }

    StringBuilder sb = new StringBuilder();
    sb.append("package ").append(service.packageName).append(";\n\n");
    sb.append("import java.math.BigDecimal;\n");
    sb.append("import java.util.UUID;\n\n");
    sb.append("public final class ").append(className).append(" {\n\n");

    for (SbeField f : msg.fields) {
      sb.append("  private final ").append(TypeMapper.publicJavaType(f, schema))
        .append(" ").append(f.name).append(";\n");
    }
    sb.append("\n");

    sb.append("  ").append(className).append("(");
    for (int i = 0; i < msg.fields.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      SbeField f = msg.fields.get(i);
      sb.append(TypeMapper.publicJavaType(f, schema)).append(" ").append(f.name);
    }
    sb.append(") {\n");
    for (SbeField f : msg.fields) {
      sb.append("    this.").append(f.name).append(" = ").append(f.name).append(";\n");
    }
    sb.append("  }\n\n");

    for (SbeField f : msg.fields) {
      sb.append("  public ").append(TypeMapper.publicJavaType(f, schema))
        .append(" ").append(f.name).append("() { return ").append(f.name).append("; }\n");
    }

    sb.append("}\n");
    write(pkgDir, className + ".java", sb.toString());
  }

  // ── Failure exception ─────────────────────────────────────────────────────

  private void emitFailureException(Path pkgDir, SbeMessage err) throws IOException {
    String className = TypeMapper.publicName(err.name);
    if (!notYetEmitted(className)) {
      return;
    }

    StringBuilder sb = new StringBuilder();
    sb.append("package ").append(service.packageName).append(";\n\n");
    sb.append("import io.scalecube.sbe.sdk.SdkException;\n");
    sb.append("import java.math.BigDecimal;\n");
    sb.append("import java.util.UUID;\n\n");
    sb.append("public final class ").append(className).append(" extends SdkException {\n\n");

    for (SbeField f : err.fields) {
      sb.append("  private final ").append(TypeMapper.publicJavaType(f, schema))
        .append(" ").append(f.name).append(";\n");
    }
    for (SbeVarData d : err.varData) {
      sb.append("  private final String ").append(d.name).append(";\n");
    }
    sb.append("\n");

    sb.append("  ").append(className).append("(");
    boolean first = true;
    for (SbeField f : err.fields) {
      if (!first) {
        sb.append(", ");
      }
      sb.append(TypeMapper.publicJavaType(f, schema)).append(" ").append(f.name);
      first = false;
    }
    for (SbeVarData d : err.varData) {
      if (!first) {
        sb.append(", ");
      }
      sb.append("String ").append(d.name);
      first = false;
    }
    sb.append(") {\n    super(");
    boolean firstMsg = true;
    for (SbeField f : err.fields) {
      if (!firstMsg) {
        sb.append(" + \": \" + ");
      }
      sb.append(f.name);
      firstMsg = false;
    }
    for (SbeVarData d : err.varData) {
      if (!firstMsg) {
        sb.append(" + \": \" + ");
      }
      sb.append(d.name);
      firstMsg = false;
    }
    sb.append(");\n");
    for (SbeField f : err.fields) {
      sb.append("    this.").append(f.name).append(" = ").append(f.name).append(";\n");
    }
    for (SbeVarData d : err.varData) {
      sb.append("    this.").append(d.name).append(" = ").append(d.name).append(";\n");
    }
    sb.append("  }\n\n");

    for (SbeField f : err.fields) {
      sb.append("  public ").append(TypeMapper.publicJavaType(f, schema))
        .append(" ").append(f.name).append("() { return ").append(f.name).append("; }\n");
    }
    for (SbeVarData d : err.varData) {
      sb.append("  public String ").append(d.name).append("() { return ").append(d.name).append("; }\n");
    }

    sb.append("}\n");
    write(pkgDir, className + ".java", sb.toString());
  }

  // ── Writer mapper ─────────────────────────────────────────────────────────

  private void emitWriterMapper(Path pkgDir, SbeMessage cmd) throws IOException {
    String builderClass = TypeMapper.publicName(cmd.name);
    String mapperClass  = builderClass + "Mapper";
    if (!notYetEmitted(mapperClass)) {
      return;
    }

    String encoderClass = cmd.name + "Encoder";
    String sbePackage   = effectivePkg(cmd.sbePackage);

    StringBuilder sb = new StringBuilder();
    sb.append("package ").append(service.packageName).append(";\n\n");
    sb.append("import ").append(sbePackage).append(".").append(encoderClass).append(";\n\n");
    sb.append("final class ").append(mapperClass).append(" {\n\n");
    sb.append("  static void write(").append(builderClass).append(" p, ").append(encoderClass).append(" enc) {\n");

    for (SbeField f : cmd.fields) {
      if (TypeMapper.isDecimal(f, schema)) {
        sb.append("    enc.").append(f.name).append("()")
          .append(".mantissa(p.").append(f.name).append(".unscaledValue().longValueExact())")
          .append(".exponent((byte) -p.").append(f.name).append(".scale());\n");
      } else if (TypeMapper.isBigDecimalExchange(f, schema)) {
        sb.append("    enc.").append(f.name).append("()")
          .append(".value(p.").append(f.name).append(".unscaledValue().longValueExact())")
          .append(".scale(p.").append(f.name).append(".scale());\n");
      } else if (TypeMapper.isUuid(f, schema)) {
        sb.append("    enc.").append(f.name).append("()")
          .append(".mostSignificantBits(p.").append(f.name).append(".getMostSignificantBits())")
          .append(".leastSignificantBits(p.").append(f.name).append(".getLeastSignificantBits());\n");
      } else if (TypeMapper.isCompositeWithFields(f, schema)) {
        sb.append("    enc.").append(f.name).append("()");
        for (SbeTypeInfo.CompositeField cf : TypeMapper.getCompositeFields(f, schema)) {
          sb.append("\n      .").append(cf.name()).append("(p.").append(f.name).append(".").append(cf.name()).append(")");
        }
        sb.append(";\n");
      } else if (TypeMapper.isEnum(f, schema)) {
        String sbePkg = enumSbePkg(f.typeName, sbePackage);
        if (f.optional) {
          sb.append("    if (p.").append(f.name).append(" != null) enc.").append(f.name).append("(")
            .append(sbePkg).append(".").append(f.typeName)
            .append(".valueOf(p.").append(f.name).append(".name()));\n");
        } else {
          sb.append("    enc.").append(f.name).append("(")
            .append(sbePkg).append(".").append(f.typeName)
            .append(".valueOf(p.").append(f.name).append(".name()));\n");
        }
      } else if (TypeMapper.isCharArray(f, schema)) {
        sb.append("    enc.").append(f.name).append("(p.").append(f.name).append(");\n");
      } else if (f.optional) {
        sb.append("    if (p.").append(f.name).append(" != null) enc.").append(f.name)
          .append("(p.").append(f.name).append(");\n");
      } else {
        sb.append("    enc.").append(f.name).append("(p.").append(f.name).append(");\n");
      }
    }

    for (SbeGroup g : cmd.groups) {
      String groupVar = g.name + "Group";
      sb.append("    var ").append(groupVar).append(" = enc.")
        .append(g.name).append("Count(p.").append(g.name).append(".size());\n");
      sb.append("    for (var item : p.").append(g.name).append(") {\n");
      sb.append("      ").append(groupVar).append(".next();\n");
      for (SbeField gf : g.fields) {
        sb.append("      ").append(groupVar).append(".").append(gf.name)
          .append("(item.").append(gf.name).append(");\n");
      }
      sb.append("    }\n");
    }

    for (SbeVarData d : cmd.varData) {
      String capName = TypeMapper.capitalize(d.name);
      sb.append("    if (p.").append(d.name).append(" != null) {\n");
      sb.append("      byte[] _").append(d.name).append("B = p.").append(d.name)
        .append(".getBytes(java.nio.charset.StandardCharsets.UTF_8);\n");
      sb.append("      enc.put").append(capName).append("(_").append(d.name)
        .append("B, 0, _").append(d.name).append("B.length);\n");
      sb.append("    }\n");
    }

    sb.append("  }\n}\n");
    write(pkgDir, mapperClass + ".java", sb.toString());
  }

  // ── Reply reader mapper ───────────────────────────────────────────────────

  private void emitReplyReaderMapper(Path pkgDir, SbeMessage msg) throws IOException {
    String resultClass  = TypeMapper.publicName(msg.name);
    String mapperClass  = resultClass + "Mapper";
    if (!notYetEmitted(mapperClass)) {
      return;
    }

    String decoderClass = msg.name + "Decoder";
    String sbePackage   = effectivePkg(msg.sbePackage);

    StringBuilder sb = new StringBuilder();
    sb.append("package ").append(service.packageName).append(";\n\n");
    sb.append("import ").append(sbePackage).append(".").append(decoderClass).append(";\n\n");
    sb.append("final class ").append(mapperClass).append(" {\n\n");
    sb.append("  static ").append(resultClass).append(" read(").append(decoderClass).append(" dec) {\n");
    sb.append("    return new ").append(resultClass).append("(\n");
    for (int i = 0; i < msg.fields.size(); i++) {
      SbeField f = msg.fields.get(i);
      String comma = (i < msg.fields.size() - 1) ? "," : "";
      sb.append("      ").append(readExpr(f, "dec")).append(comma).append("\n");
    }
    sb.append("    );\n  }\n}\n");
    write(pkgDir, mapperClass + ".java", sb.toString());
  }

  // ── Error reader mapper ───────────────────────────────────────────────────

  private void emitErrorReaderMapper(Path pkgDir, SbeMessage err) throws IOException {
    String exClass      = TypeMapper.publicName(err.name);
    String mapperClass  = exClass + "Mapper";
    if (!notYetEmitted(mapperClass)) {
      return;
    }

    String decoderClass = err.name + "Decoder";
    String sbePackage   = effectivePkg(err.sbePackage);

    StringBuilder sb = new StringBuilder();
    sb.append("package ").append(service.packageName).append(";\n\n");
    sb.append("import ").append(sbePackage).append(".").append(decoderClass).append(";\n\n");
    sb.append("final class ").append(mapperClass).append(" {\n\n");
    sb.append("  static ").append(exClass).append(" read(").append(decoderClass).append(" dec) {\n");
    // Decode varData fields first using byte[] (getXxx(Appendable) not always available)
    for (SbeVarData d : err.varData) {
      String capName = TypeMapper.capitalize(d.name);
      sb.append("    int _").append(d.name).append("Len = dec.").append(d.name).append("Length();\n");
      sb.append("    byte[] _").append(d.name).append("B = new byte[_").append(d.name).append("Len];\n");
      sb.append("    dec.get").append(capName).append("(_").append(d.name).append("B, 0, _").append(d.name).append("Len);\n");
    }
    sb.append("    return new ").append(exClass).append("(\n");

    int total = err.fields.size() + err.varData.size();
    int idx = 0;
    for (SbeField f : err.fields) {
      String comma = (idx < total - 1) ? "," : "";
      sb.append("      ").append(readExpr(f, "dec")).append(comma).append("\n");
      idx++;
    }
    for (SbeVarData d : err.varData) {
      String comma = (idx < total - 1) ? "," : "";
      sb.append("      new String(_").append(d.name).append("B, java.nio.charset.StandardCharsets.UTF_8)").append(comma).append("\n");
      idx++;
    }
    sb.append("    );\n  }\n}\n");
    write(pkgDir, mapperClass + ".java", sb.toString());
  }

  // ── Service interface ─────────────────────────────────────────────────────

  private void emitServiceInterface(Path pkgDir) throws IOException {
    String iface = service.serviceName;
    StringBuilder sb = new StringBuilder();

    sb.append("package ").append(service.packageName).append(";\n\n");
    sb.append("import io.scalecube.sbe.sdk.SdkException;\n");
    sb.append("import java.util.function.BiConsumer;\n");
    sb.append("import java.util.function.Consumer;\n");
    sb.append("import reactor.core.publisher.Flux;\n");
    sb.append("import reactor.core.publisher.Mono;\n\n");

    sb.append("public interface ").append(iface).append(" {\n\n");

    for (OperationDescriptor op : service.operations) {
      if (op.isSubscription()) {
        String eventClass = TypeMapper.publicName(schema.messages.get(op.event).name);
        if (op.command != null) {
          String builderClass = TypeMapper.publicName(schema.messages.get(op.command).name);
          sb.append("  Flux<").append(eventClass).append("> ").append(op.name)
            .append("(Consumer<").append(builderClass).append("> spec);\n\n");
        } else {
          sb.append("  Flux<").append(eventClass).append("> ").append(op.name).append("();\n\n");
        }

      } else if (op.isFireAndForget()) {
        String builderClass = TypeMapper.publicName(schema.messages.get(op.command).name);
        sb.append("  void ").append(op.name)
          .append("(Consumer<").append(builderClass).append("> spec);\n\n");
        sb.append("  Mono<Void> ").append(op.name).append("Async")
          .append("(Consumer<").append(builderClass).append("> spec);\n\n");

      } else if (op.isRequestMany()) {
        String builderClass = TypeMapper.publicName(schema.messages.get(op.command).name);
        String eventClass   = TypeMapper.publicName(schema.messages.get(op.event).name);
        sb.append("  Flux<").append(eventClass).append("> ").append(op.name)
          .append("(Consumer<").append(builderClass).append("> spec);\n\n");

      } else {
        String builderClass = TypeMapper.publicName(schema.messages.get(op.command).name);
        String resultClass  = TypeMapper.publicName(schema.messages.get(op.reply).name);
        String errClass     = op.onError != null
            ? TypeMapper.publicName(schema.messages.get(op.onError).name) : null;

        // sync
        String throwsClause = errClass != null ? " // throws " + errClass : "";
        sb.append("  ").append(resultClass).append(" ").append(op.name)
          .append("(Consumer<").append(builderClass).append("> spec);")
          .append(throwsClause).append("\n\n");

        // Mono async
        sb.append("  Mono<").append(resultClass).append("> ").append(op.name).append("Async")
          .append("(Consumer<").append(builderClass).append("> spec);\n\n");

        // error callback variants
        for (ErrorVariantDescriptor ev : op.errorVariants) {
          if (ev.isWithRequest()) {
            sb.append("  void ").append(ev.name)
              .append("(Consumer<").append(builderClass).append("> spec, ")
              .append("BiConsumer<").append(builderClass).append(", ").append(errClass).append("> onError);\n\n");
          } else {
            sb.append("  void ").append(ev.name)
              .append("(Consumer<").append(builderClass).append("> spec, ")
              .append("Consumer<").append(errClass).append("> onError);\n\n");
          }
        }
      }
    }

    sb.append("}\n");
    write(pkgDir, iface + ".java", sb.toString());
  }

  // ── Service impl ──────────────────────────────────────────────────────────

  private void emitServiceImpl(Path pkgDir) throws IOException {
    String iface     = service.serviceName;
    String implClass = iface + "Impl";

    StringBuilder sb = new StringBuilder();
    sb.append("package ").append(service.packageName).append(";\n\n");
    sb.append("import io.scalecube.sbe.sdk.Transport;\n");
    sb.append("import java.util.function.BiConsumer;\n");
    sb.append("import java.util.function.Consumer;\n");
    sb.append("import org.agrona.DirectBuffer;\n");
    sb.append("import org.agrona.ExpandableArrayBuffer;\n");
    sb.append("import org.agrona.concurrent.UnsafeBuffer;\n");
    sb.append("import reactor.core.publisher.Flux;\n");
    sb.append("import reactor.core.publisher.Mono;\n");

    // collect all encoder/decoder imports
    for (OperationDescriptor op : service.operations) {
      if (op.isSubscription()) {
        String evtPkg = effectivePkg(schema.messages.get(op.event).sbePackage);
        sb.append("import ").append(evtPkg).append(".").append(op.event).append("Decoder;\n");
        if (op.command != null) {
          String cmdPkg = effectivePkg(schema.messages.get(op.command).sbePackage);
          sb.append("import ").append(cmdPkg).append(".").append(op.command).append("Encoder;\n");
        }
      } else if (op.isFireAndForget()) {
        String cmdPkg = effectivePkg(schema.messages.get(op.command).sbePackage);
        sb.append("import ").append(cmdPkg).append(".").append(op.command).append("Encoder;\n");
        if (op.reply != null) {
          String replyPkg = effectivePkg(schema.messages.get(op.reply).sbePackage);
          sb.append("import ").append(replyPkg).append(".").append(op.reply).append("Decoder;\n");
        }
      } else if (op.isRequestMany()) {
        String cmdPkg = effectivePkg(schema.messages.get(op.command).sbePackage);
        String evtPkg = effectivePkg(schema.messages.get(op.event).sbePackage);
        sb.append("import ").append(cmdPkg).append(".").append(op.command).append("Encoder;\n");
        sb.append("import ").append(evtPkg).append(".").append(op.event).append("Decoder;\n");
      } else {
        String cmdPkg   = effectivePkg(schema.messages.get(op.command).sbePackage);
        String replyPkg = effectivePkg(schema.messages.get(op.reply).sbePackage);
        sb.append("import ").append(cmdPkg).append(".").append(op.command).append("Encoder;\n");
        sb.append("import ").append(replyPkg).append(".").append(op.reply).append("Decoder;\n");
        if (op.onError != null) {
          String errPkg = effectivePkg(schema.messages.get(op.onError).sbePackage);
          sb.append("import ").append(errPkg).append(".").append(op.onError).append("Decoder;\n");
        }
      }
    }
    sb.append("\n");

    sb.append("public final class ").append(implClass).append(" implements ").append(iface).append(" {\n\n");
    sb.append("  private final Transport transport;\n\n");
    sb.append("  public ").append(implClass).append("(Transport transport) {\n");
    sb.append("    this.transport = transport;\n  }\n\n");

    for (OperationDescriptor op : service.operations) {
      if (op.isSubscription()) {
        emitSubscriptionMethod(sb, op);
      } else if (op.isFireAndForget()) {
        emitFireAndForgetMethod(sb, op);
      } else if (op.isRequestMany()) {
        emitRequestManyMethod(sb, op);
      } else {
        emitBlockingMethods(sb, op);
      }
    }

    sb.append("}\n");
    write(pkgDir, implClass + ".java", sb.toString());
  }

  private void emitSubscriptionMethod(StringBuilder sb, OperationDescriptor op) {
    String eventClass   = TypeMapper.publicName(schema.messages.get(op.event).name);
    String decoderClass = op.event + "Decoder";
    String mapperClass  = eventClass + "Mapper";
    String hdrDecFqn    = effectivePkg(schema.messages.get(op.event).sbePackage) + ".MessageHeaderDecoder";

    sb.append("  @Override\n");
    if (op.command != null) {
      String builderClass = TypeMapper.publicName(schema.messages.get(op.command).name);
      String encoderClass = op.command + "Encoder";
      String writerMapper = builderClass + "Mapper";
      String cmdSbePkg    = effectivePkg(schema.messages.get(op.command).sbePackage);
      String hdrEncFqn    = cmdSbePkg + ".MessageHeaderEncoder";
      sb.append("  public Flux<").append(eventClass).append("> ").append(op.name)
        .append("(Consumer<").append(builderClass).append("> spec) {\n");
      sb.append("    ").append(builderClass).append(" req = new ").append(builderClass).append("();\n");
      sb.append("    spec.accept(req);\n");
      sb.append("    ExpandableArrayBuffer cmdBuf = new ExpandableArrayBuffer(256);\n");
      sb.append("    ").append(encoderClass).append(" enc = new ").append(encoderClass).append("();\n");
      sb.append("    ").append(hdrEncFqn).append(" hdrEnc = new ").append(hdrEncFqn).append("();\n");
      sb.append("    enc.wrapAndApplyHeader(cmdBuf, 0, hdrEnc);\n");
      sb.append("    ").append(writerMapper).append(".write(req, enc);\n");
      sb.append("    int len = ").append(hdrEncFqn).append(".ENCODED_LENGTH + enc.encodedLength();\n");
      sb.append("    transport.request(new UnsafeBuffer(cmdBuf, 0, len), 0, len);\n");
    } else {
      sb.append("  public Flux<").append(eventClass).append("> ").append(op.name).append("() {\n");
    }
    sb.append("    return transport.subscribe(\"").append(op.topic).append("\")\n");
    sb.append("        .filter(buf -> new ").append(hdrDecFqn).append("().wrap(buf, 0).templateId() == ")
      .append(decoderClass).append(".TEMPLATE_ID)\n");
    sb.append("        .map(buf -> {\n");
    sb.append("          ").append(hdrDecFqn).append(" hdr = new ").append(hdrDecFqn).append("().wrap(buf, 0);\n");
    sb.append("          ").append(decoderClass).append(" dec = new ").append(decoderClass).append("()\n");
    sb.append("              .wrap(buf, hdr.encodedLength(), ")
      .append(decoderClass).append(".BLOCK_LENGTH, ").append(decoderClass).append(".SCHEMA_VERSION);\n");
    sb.append("          return ").append(mapperClass).append(".read(dec);\n");
    sb.append("        });\n");
    sb.append("  }\n\n");
  }

  private void emitFireAndForgetMethod(StringBuilder sb, OperationDescriptor op) {
    String builderClass = TypeMapper.publicName(schema.messages.get(op.command).name);
    String encoderClass = op.command + "Encoder";
    String writerMapper = builderClass + "Mapper";
    String cmdSbePkg    = effectivePkg(schema.messages.get(op.command).sbePackage);

    sb.append("  @Override\n");
    sb.append("  public void ").append(op.name)
      .append("(Consumer<").append(builderClass).append("> spec) {\n");
    sb.append("    ").append(builderClass).append(" req = new ").append(builderClass).append("();\n");
    sb.append("    spec.accept(req);\n");
    appendEncodeAndSend(sb, encoderClass, writerMapper, cmdSbePkg);
    sb.append("  }\n\n");

    sb.append("  @Override\n");
    sb.append("  public Mono<Void> ").append(op.name).append("Async")
      .append("(Consumer<").append(builderClass).append("> spec) {\n");
    sb.append("    return Mono.fromRunnable(() -> ").append(op.name).append("(spec));\n");
    sb.append("  }\n\n");
  }

  private void emitRequestManyMethod(StringBuilder sb, OperationDescriptor op) {
    String builderClass = TypeMapper.publicName(schema.messages.get(op.command).name);
    String eventClass   = TypeMapper.publicName(schema.messages.get(op.event).name);
    String encoderClass = op.command + "Encoder";
    String decoderClass = op.event + "Decoder";
    String writerMapper = builderClass + "Mapper";
    String eventMapper  = eventClass + "Mapper";
    String cmdSbePkg    = effectivePkg(schema.messages.get(op.command).sbePackage);
    String evtSbePkg    = effectivePkg(schema.messages.get(op.event).sbePackage);
    String hdrEncFqn    = cmdSbePkg + ".MessageHeaderEncoder";
    String hdrDecFqn    = evtSbePkg + ".MessageHeaderDecoder";

    sb.append("  @Override\n");
    sb.append("  public Flux<").append(eventClass).append("> ").append(op.name)
      .append("(Consumer<").append(builderClass).append("> spec) {\n");
    sb.append("    ").append(builderClass).append(" req = new ").append(builderClass).append("();\n");
    sb.append("    spec.accept(req);\n");
    sb.append("    ExpandableArrayBuffer buf = new ExpandableArrayBuffer(256);\n");
    sb.append("    ").append(encoderClass).append(" enc = new ").append(encoderClass).append("();\n");
    sb.append("    ").append(hdrEncFqn).append(" hdrEnc = new ").append(hdrEncFqn).append("();\n");
    sb.append("    enc.wrapAndApplyHeader(buf, 0, hdrEnc);\n");
    sb.append("    ").append(writerMapper).append(".write(req, enc);\n");
    sb.append("    int len = ").append(hdrEncFqn).append(".ENCODED_LENGTH + enc.encodedLength();\n");
    sb.append("    return transport.requestMany(new UnsafeBuffer(buf, 0, len), 0, len)\n");
    sb.append("        .filter(b -> new ").append(hdrDecFqn).append("().wrap(b, 0).templateId() == ")
      .append(decoderClass).append(".TEMPLATE_ID)\n");
    sb.append("        .map(b -> {\n");
    sb.append("          ").append(hdrDecFqn).append(" hdr = new ").append(hdrDecFqn).append("().wrap(b, 0);\n");
    sb.append("          ").append(decoderClass).append(" dec = new ").append(decoderClass).append("()\n");
    sb.append("              .wrap(b, hdr.encodedLength(), ")
      .append(decoderClass).append(".BLOCK_LENGTH, ").append(decoderClass).append(".SCHEMA_VERSION);\n");
    sb.append("          return ").append(eventMapper).append(".read(dec);\n");
    sb.append("        });\n");
    sb.append("  }\n\n");
  }

  private void emitBlockingMethods(StringBuilder sb, OperationDescriptor op) {
    String builderClass  = TypeMapper.publicName(schema.messages.get(op.command).name);
    String resultClass   = TypeMapper.publicName(schema.messages.get(op.reply).name);
    String encoderClass  = op.command + "Encoder";
    String replyDecClass = op.reply + "Decoder";
    String writerMapper  = builderClass + "Mapper";
    String replyMapper   = resultClass + "Mapper";
    String errClass      = op.onError != null
        ? TypeMapper.publicName(schema.messages.get(op.onError).name) : null;
    String errDecClass   = op.onError != null ? op.onError + "Decoder" : null;
    String errMapper     = errClass != null ? errClass + "Mapper" : null;
    String hdrEncFqn     = effectivePkg(schema.messages.get(op.command).sbePackage) + ".MessageHeaderEncoder";
    String hdrDecFqn     = effectivePkg(schema.messages.get(op.reply).sbePackage) + ".MessageHeaderDecoder";

    // private doX() helper — needed so BiConsumer variant can pass the req POJO back
    String doMethod = "do" + TypeMapper.capitalize(op.name);
    sb.append("  private ").append(resultClass).append(" ").append(doMethod)
      .append("(").append(builderClass).append(" req) {\n");
    sb.append("    ExpandableArrayBuffer buf = new ExpandableArrayBuffer(256);\n");
    sb.append("    ").append(encoderClass).append(" enc = new ").append(encoderClass).append("();\n");
    sb.append("    ").append(hdrEncFqn).append(" hdrEnc = new ").append(hdrEncFqn).append("();\n");
    sb.append("    enc.wrapAndApplyHeader(buf, 0, hdrEnc);\n");
    sb.append("    ").append(writerMapper).append(".write(req, enc);\n");
    sb.append("    int len = ").append(hdrEncFqn).append(".ENCODED_LENGTH + enc.encodedLength();\n");
    sb.append("    DirectBuffer reply = transport.request(new UnsafeBuffer(buf, 0, len), 0, len);\n");
    sb.append("    ").append(hdrDecFqn).append(" hdrDec = new ").append(hdrDecFqn).append("().wrap(reply, 0);\n");
    sb.append("    int templateId = hdrDec.templateId();\n");
    sb.append("    int bodyOffset = hdrDec.encodedLength();\n");
    sb.append("    if (templateId == ").append(replyDecClass).append(".TEMPLATE_ID) {\n");
    sb.append("      ").append(replyDecClass).append(" dec = new ").append(replyDecClass)
      .append("().wrap(reply, bodyOffset, ").append(replyDecClass)
      .append(".BLOCK_LENGTH, ").append(replyDecClass).append(".SCHEMA_VERSION);\n");
    sb.append("      return ").append(replyMapper).append(".read(dec);\n    }\n");
    if (errClass != null) {
      sb.append("    if (templateId == ").append(errDecClass).append(".TEMPLATE_ID) {\n");
      sb.append("      ").append(errDecClass).append(" dec = new ").append(errDecClass)
        .append("().wrap(reply, bodyOffset, ").append(errDecClass)
        .append(".BLOCK_LENGTH, ").append(errDecClass).append(".SCHEMA_VERSION);\n");
      sb.append("      throw ").append(errMapper).append(".read(dec);\n    }\n");
    }
    sb.append("    throw new IllegalStateException(\"Unexpected templateId: \" + templateId);\n");
    sb.append("  }\n\n");

    // sync
    sb.append("  @Override\n");
    sb.append("  public ").append(resultClass).append(" ").append(op.name)
      .append("(Consumer<").append(builderClass).append("> spec) {\n");
    sb.append("    ").append(builderClass).append(" req = new ").append(builderClass).append("();\n");
    sb.append("    spec.accept(req);\n");
    sb.append("    return ").append(doMethod).append("(req);\n");
    sb.append("  }\n\n");

    // Mono async
    sb.append("  @Override\n");
    sb.append("  public Mono<").append(resultClass).append("> ").append(op.name)
      .append("Async(Consumer<").append(builderClass).append("> spec) {\n");
    sb.append("    return Mono.fromCallable(() -> ").append(op.name).append("(spec));\n");
    sb.append("  }\n\n");

    // error callback variants
    for (ErrorVariantDescriptor ev : op.errorVariants) {
      sb.append("  @Override\n");
      if (ev.isWithRequest()) {
        sb.append("  public void ").append(ev.name)
            .append("(Consumer<").append(builderClass).append("> spec, ")
            .append("BiConsumer<").append(builderClass).append(", ")
            .append(errClass).append("> onError) {\n");
        sb.append("    ").append(builderClass).append(" req = new ")
            .append(builderClass).append("();\n");
        sb.append("    spec.accept(req);\n");
        sb.append("    try {\n");
        sb.append("      ").append(doMethod).append("(req);\n");
        sb.append("    } catch (").append(errClass).append(" e) {\n");
        sb.append("      onError.accept(req, e);\n");
        sb.append("    }\n");
      } else {
        sb.append("  public void ").append(ev.name)
            .append("(Consumer<").append(builderClass).append("> spec, ")
            .append("Consumer<").append(errClass).append("> onError) {\n");
        sb.append("    try {\n");
        sb.append("      ").append(op.name).append("(spec);\n");
        sb.append("    } catch (").append(errClass).append(" e) {\n");
        sb.append("      onError.accept(e);\n");
        sb.append("    }\n");
      }
      sb.append("  }\n\n");
    }
  }

  private void appendEncodeAndSend(StringBuilder sb, String encoderClass, String writerMapper, String cmdSbePkg) {
    String hdrEncFqn = cmdSbePkg + ".MessageHeaderEncoder";
    sb.append("    ExpandableArrayBuffer buf = new ExpandableArrayBuffer(256);\n");
    sb.append("    ").append(encoderClass).append(" enc = new ").append(encoderClass).append("();\n");
    sb.append("    ").append(hdrEncFqn).append(" hdrEnc = new ").append(hdrEncFqn).append("();\n");
    sb.append("    enc.wrapAndApplyHeader(buf, 0, hdrEnc);\n");
    sb.append("    ").append(writerMapper).append(".write(req, enc);\n");
    sb.append("    int len = ").append(hdrEncFqn).append(".ENCODED_LENGTH + enc.encodedLength();\n");
    sb.append("    transport.request(new UnsafeBuffer(buf, 0, len), 0, len);\n");
  }

  // ── utility ───────────────────────────────────────────────────────────────

  private void emitCompositePojos(Path pkgDir) throws IOException {
    for (SbeTypeInfo type : schema.types.values()) {
      if (type.kind != SbeTypeKind.COMPOSITE) {
        continue;
      }
      if (type.compositeFields == null || type.compositeFields.isEmpty()) {
        continue;
      }
      if (!notYetEmitted(type.name)) {
        continue;
      }

      StringBuilder sb = new StringBuilder();
      sb.append("package ").append(service.packageName).append(";\n\n");
      sb.append("public final class ").append(type.name).append(" {\n\n");
      for (SbeTypeInfo.CompositeField cf : type.compositeFields) {
        sb.append("  public final ").append(compositePrimType(cf.sbeType()))
          .append(" ").append(cf.name()).append(";\n");
      }
      sb.append("\n  public ").append(type.name).append("(");
      boolean first = true;
      for (SbeTypeInfo.CompositeField cf : type.compositeFields) {
        if (!first) {
          sb.append(", ");
        }
        sb.append(compositePrimType(cf.sbeType())).append(" ").append(cf.name());
        first = false;
      }
      sb.append(") {\n");
      for (SbeTypeInfo.CompositeField cf : type.compositeFields) {
        sb.append("    this.").append(cf.name()).append(" = ").append(cf.name()).append(";\n");
      }
      sb.append("  }\n}\n");
      write(pkgDir, type.name + ".java", sb.toString());
    }
  }

  private static String compositePrimType(String sbeType) {
    return switch (sbeType) {
      case "int8"    -> "byte";
      case "int16"   -> "short";
      case "int32"   -> "int";
      case "int64"   -> "long";
      case "uint8"   -> "short";
      case "uint16"  -> "int";
      case "uint32"  -> "long";
      case "uint64"  -> "long";
      case "boolean" -> "boolean";
      case "char"    -> "char";
      default        -> "int";
    };
  }

  private String readExpr(SbeField f, String dec) {
    if (TypeMapper.isDecimal(f, schema)) {
      return "new java.math.BigDecimal(" + dec + "." + f.name + "().mantissa())"
           + ".scaleByPowerOfTen(" + dec + "." + f.name + "().exponent())";
    } else if (TypeMapper.isBigDecimalExchange(f, schema)) {
      return "new java.math.BigDecimal(" + dec + "." + f.name + "().value())"
           + ".movePointLeft(" + dec + "." + f.name + "().scale())";
    } else if (TypeMapper.isUuid(f, schema)) {
      return "new java.util.UUID(" + dec + "." + f.name + "().mostSignificantBits(), "
           + dec + "." + f.name + "().leastSignificantBits())";
    } else if (TypeMapper.isCompositeWithFields(f, schema)) {
      String typeName = TypeMapper.publicJavaType(f, schema);
      StringBuilder args = new StringBuilder();
      for (SbeTypeInfo.CompositeField cf : TypeMapper.getCompositeFields(f, schema)) {
        if (args.length() > 0) {
          args.append(", ");
        }
        args.append(dec).append(".").append(f.name).append("().").append(cf.name()).append("()");
      }
      return "new " + typeName + "(" + args + ")";
    } else if (TypeMapper.isEnum(f, schema)) {
      return f.typeName + ".valueOf(" + dec + "." + f.name + "().name())";
    } else {
      return dec + "." + f.name + "()";
    }
  }

  private void write(Path dir, String filename, String content) throws IOException {
    Files.writeString(dir.resolve(filename), content);
    System.out.println("  [emit] " + filename);
  }

  private String effectivePkg(String msgSbePackage) {
    return msgSbePackage != null ? msgSbePackage : schema.packageName;
  }

  private String enumSbePkg(String typeName, String fallback) {
    SbeTypeInfo t = schema.types.get(typeName);
    return (t != null && t.sbePackage != null) ? t.sbePackage : fallback;
  }
}
