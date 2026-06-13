package io.scalecube.sbe.codegen.generator.model;

import java.util.Map;

public class SbeSchema {
  public final String packageName;
  public final int id;
  /** Keyed by type name. Does NOT include built-in primitive names. */
  public final Map<String, SbeTypeInfo> types;
  /** Keyed by message name. */
  public final Map<String, SbeMessage> messages;

  public SbeSchema(String packageName, int id,
      Map<String, SbeTypeInfo> types, Map<String, SbeMessage> messages) {
    this.packageName = packageName;
    this.id = id;
    this.types = types;
    this.messages = messages;
  }
}
