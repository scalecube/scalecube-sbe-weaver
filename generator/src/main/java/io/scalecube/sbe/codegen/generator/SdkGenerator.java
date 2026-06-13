package io.scalecube.sbe.codegen.generator;

import io.scalecube.sbe.codegen.generator.emit.JavaEmitter;
import io.scalecube.sbe.codegen.generator.model.OperationDescriptor;
import io.scalecube.sbe.codegen.generator.model.SbeSchema;
import io.scalecube.sbe.codegen.generator.model.ServiceDescriptor;
import io.scalecube.sbe.codegen.generator.parser.SbeClassInspector;
import io.scalecube.sbe.codegen.generator.parser.ServiceDescriptorParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Entry point: SdkGenerator &lt;sbeRootPackage&gt; &lt;yaml-file-or-dir&gt; &lt;outputDir&gt;
 *
 * <p>The first argument is the directory containing SBE-generated Java source files
 * (e.g. target/generated-sources/sbe).  If the second argument is a directory,
 * all *.yaml files inside it are processed.
 */
public class SdkGenerator {

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      System.err.println("Usage: SdkGenerator <sbeRootPackage> <yaml-file-or-dir> <outputDir>");
      System.exit(1);
    }
    new SdkGenerator().runAll(args[0], args[1], args[2]);
  }

  public void runAll(String sbeRootPackage, String yamlFileOrDir, String outputDir)
      throws Exception {
    Path yamlPath = Path.of(yamlFileOrDir);
    List<Path> yamls;
    if (Files.isDirectory(yamlPath)) {
      try (Stream<Path> stream = Files.list(yamlPath)) {
        yamls = stream
            .filter(p -> p.getFileName().toString().endsWith(".yaml"))
            .sorted()
            .toList();
      }
    } else {
      yamls = List.of(yamlPath);
    }

    List<String> failures = new ArrayList<>();
    for (Path yaml : yamls) {
      try {
        run(sbeRootPackage, yaml.toString(), outputDir);
      } catch (IllegalArgumentException e) {
        // Validation failure: message not found in current schema — skip with warning.
        // This is normal when scanning a directory that contains service descriptors
        // for messages not present in the current SBE schema.
        System.out.println("SdkGenerator: SKIP " + yaml.getFileName() + " — " + e.getMessage());
      } catch (Exception e) {
        System.err.println("SdkGenerator: FAILED " + yaml.getFileName() + " — " + e.getMessage());
        failures.add(yaml.getFileName() + ": " + e.getMessage());
      }
    }

    if (!failures.isEmpty()) {
      throw new RuntimeException("SdkGenerator: " + failures.size() + " YAML(s) failed:\n"
          + String.join("\n", failures));
    }
  }

  public void run(String sbeRootPackage, String serviceYaml, String outputDir) throws Exception {
    System.out.println("SdkGenerator: sbe="  + sbeRootPackage);
    System.out.println("              yaml=" + serviceYaml);
    System.out.println("              out="  + outputDir);

    SbeSchema schema = new SbeClassInspector().inspect(sbeRootPackage);
    ServiceDescriptor service = new ServiceDescriptorParser().parse(serviceYaml);

    validate(schema, service);

    Path outPath = Path.of(outputDir);
    Files.createDirectories(outPath);

    new JavaEmitter(schema, service, outPath).emit();

    System.out.println("SdkGenerator: done — "
        + service.operations.size() + " operation(s) emitted.");
  }

  private void validate(SbeSchema schema, ServiceDescriptor service) {
    for (OperationDescriptor op : service.operations) {
      if (op.isSubscription()) {
        if (op.topic == null || op.topic.isBlank()) {
          throw new IllegalArgumentException(
              "Subscription operation '" + op.name + "' must have a 'topic'");
        }
        requireMessage(schema, op.event, "event", op.name);
        if (op.command != null) {
          requireMessage(schema, op.command, "command", op.name);
        }
      } else if (op.isFireAndForget()) {
        requireMessage(schema, op.command, "command", op.name);
      } else if (op.isRequestMany()) {
        requireMessage(schema, op.command, "command", op.name);
        requireMessage(schema, op.event,   "event",   op.name);
      } else {
        requireMessage(schema, op.command, "command", op.name);
        requireMessage(schema, op.reply,   "reply",   op.name);
        if (op.onError != null) {
          requireMessage(schema, op.onError, "onError", op.name);
        }
        if (!op.errorVariants.isEmpty() && op.onError == null) {
          throw new IllegalArgumentException(
              "Operation '" + op.name + "' declares errorVariants but has no 'onError' message");
        }
      }
    }
  }

  private void requireMessage(SbeSchema schema, String msgName, String role, String opName) {
    if (msgName == null || msgName.isBlank()) {
      throw new IllegalArgumentException(
          "Operation '" + opName + "' is missing required field '" + role + "'");
    }
    if (!schema.messages.containsKey(msgName)) {
      throw new IllegalArgumentException(
          "Operation '" + opName + "' references unknown " + role + " message '" + msgName + "'");
    }
  }
}
