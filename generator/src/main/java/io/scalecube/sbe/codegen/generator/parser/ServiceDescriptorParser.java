package io.scalecube.sbe.codegen.generator.parser;

import io.scalecube.sbe.codegen.generator.model.ErrorVariantDescriptor;
import io.scalecube.sbe.codegen.generator.model.OperationDescriptor;
import io.scalecube.sbe.codegen.generator.model.ServiceDescriptor;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

public class ServiceDescriptorParser {

  @SuppressWarnings("unchecked")
  public ServiceDescriptor parse(String yamlPath) throws Exception {
    Yaml yaml = new Yaml();
    Map<String, Object> root;
    try (FileInputStream fis = new FileInputStream(yamlPath)) {
      root = yaml.load(fis);
    }

    // "service" is the canonical key; tolerate any accidental prefix (e.g. "yeservice")
    String serviceName = root.entrySet().stream()
        .filter(e -> e.getKey().endsWith("service"))
        .map(e -> (String) e.getValue())
        .findFirst().orElse(null);
    String packageName = (String) root.get("package");

    List<Map<String, Object>> opList = (List<Map<String, Object>>) root.get("operations");
    List<OperationDescriptor> operations = new ArrayList<>();

    for (Map<String, Object> op : opList) {
      String style   = (String) op.get("style");
      String name    = (String) op.get("name");

      // request-reply / fireAndForget fields
      String command = (String) op.get("command");
      String reply   = (String) op.get("reply");
      String onError = (String) op.get("onError");

      if (name == null && command != null) {
        name = defaultOperationName(command);
      }

      // subscription fields
      String topic = (String) op.get("topic");
      String event = (String) op.get("event");

      // error variants
      List<ErrorVariantDescriptor> errorVariants = new ArrayList<>();
      List<Map<String, Object>> variants = (List<Map<String, Object>>) op.get("errorVariants");
      if (variants != null) {
        for (Map<String, Object> v : variants) {
          errorVariants.add(new ErrorVariantDescriptor(
              (String) v.get("name"),
              (String) v.get("style")));
        }
      }

      operations.add(new OperationDescriptor(
          name, command, reply, onError, errorVariants, topic, event, style));
    }

    return new ServiceDescriptor(serviceName, packageName, operations);
  }

  /** Strip trailing "Command" and decapitalize. */
  private static String defaultOperationName(String command) {
    String stripped = command.endsWith("Command")
        ? command.substring(0, command.length() - "Command".length())
        : command;
    return Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1);
  }
}
