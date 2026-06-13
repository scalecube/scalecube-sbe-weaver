package io.scalecube.sbe.codegen.generator.model;

import java.util.List;

public class SbeMessage {
  public final String name;
  public final int id;
  public final List<SbeField> fields;
  public final List<SbeGroup> groups;
  public final List<SbeVarData> varData;
  /** Fully-qualified Java package of the SBE-generated encoder/decoder for this message. */
  public final String sbePackage;

  public SbeMessage(
      String name, int id, List<SbeField> fields, List<SbeGroup> groups, List<SbeVarData> varData,
      String sbePackage) {
    this.name = name;
    this.id = id;
    this.fields = fields;
    this.groups = groups;
    this.varData = varData;
    this.sbePackage = sbePackage;
  }

  public SbeMessage(
      String name, int id, List<SbeField> fields, List<SbeGroup> groups, List<SbeVarData> varData) {
    this(name, id, fields, groups, varData, null);
  }
}
