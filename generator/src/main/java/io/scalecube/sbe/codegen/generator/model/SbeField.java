package io.scalecube.sbe.codegen.generator.model;

public class SbeField {
  public final String name;
  public final int id;
  public final String typeName;
  public final boolean optional;

  public SbeField(String name, int id, String typeName, boolean optional) {
    this.name = name;
    this.id = id;
    this.typeName = typeName;
    this.optional = optional;
  }
}
