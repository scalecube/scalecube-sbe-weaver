package io.scalecube.sbe.codegen.generator.model;

import java.util.List;

public class SbeGroup {
  public final String name;
  public final int id;
  public final List<SbeField> fields;

  public SbeGroup(String name, int id, List<SbeField> fields) {
    this.name = name;
    this.id = id;
    this.fields = fields;
  }
}
