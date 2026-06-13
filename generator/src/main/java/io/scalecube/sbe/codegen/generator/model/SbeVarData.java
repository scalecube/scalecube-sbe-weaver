package io.scalecube.sbe.codegen.generator.model;

public class SbeVarData {
  public final String name;
  public final int id;
  public final String typeName;

  public SbeVarData(String name, int id, String typeName) {
    this.name = name;
    this.id = id;
    this.typeName = typeName;
  }
}
