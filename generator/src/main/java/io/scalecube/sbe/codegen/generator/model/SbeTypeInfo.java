package io.scalecube.sbe.codegen.generator.model;

import java.util.List;

public class SbeTypeInfo {
  public final String name;
  public final SbeTypeKind kind;
  /** For PRIMITIVE: the primitive type string (e.g. "int32"). */
  public final String primitiveType;
  /** For CHAR_ARRAY: fixed length. */
  public final int length;
  /** For ENUM: underlying encoding type. */
  public final String encodingType;
  /** For ENUM: ordered valid values. */
  public final List<SbeEnumValue> enumValues;

  /** For COMPOSITE/ENUM: the Java package of the SBE-generated class.
   * Null for primitives/charArrays. */
  public final String sbePackage;

  /**
   * For COMPOSITE types with inspected sub-fields (e.g. Time, TradingDays).
   * Null when the composite maps to a Java stdlib type (UUID, BigDecimal) or was not inspectable.
   */
  public final List<CompositeField> compositeFields;

  /** A sub-field of a composite type, extracted from the SBE decoder class. */
  public record CompositeField(String name, String sbeType) {}

  private SbeTypeInfo(String name, SbeTypeKind kind, String primitiveType, int length,
                      String encodingType, List<SbeEnumValue> enumValues,
                      String sbePackage, List<CompositeField> compositeFields) {
    this.name = name;
    this.kind = kind;
    this.primitiveType = primitiveType;
    this.length = length;
    this.encodingType = encodingType;
    this.enumValues = enumValues;
    this.sbePackage = sbePackage;
    this.compositeFields = compositeFields;
  }

  public static SbeTypeInfo primitive(String name, String primitiveType) {
    return new SbeTypeInfo(
        name, SbeTypeKind.PRIMITIVE, primitiveType, 0, null, List.of(), null, null);
  }

  public static SbeTypeInfo charArray(String name, int length) {
    return new SbeTypeInfo(
        name, SbeTypeKind.CHAR_ARRAY, "char", length, null, List.of(), null, null);
  }

  public static SbeTypeInfo composite(String name) {
    return new SbeTypeInfo(name, SbeTypeKind.COMPOSITE, null, 0, null, List.of(), null, null);
  }

  public static SbeTypeInfo composite(String name, String sbePackage) {
    return new SbeTypeInfo(name, SbeTypeKind.COMPOSITE, null, 0, null, List.of(), sbePackage, null);
  }

  public static SbeTypeInfo compositeWithFields(String name, String sbePackage,
                                                List<CompositeField> compositeFields) {
    return new SbeTypeInfo(name, SbeTypeKind.COMPOSITE, null, 0, null, List.of(), sbePackage,
        compositeFields);
  }

  public static SbeTypeInfo enumType(String name, String encodingType, List<SbeEnumValue> values) {
    return new SbeTypeInfo(name, SbeTypeKind.ENUM, null, 0, encodingType, values, null, null);
  }

  public static SbeTypeInfo enumType(String name, String encodingType, List<SbeEnumValue> values,
                                     String sbePackage) {
    return new SbeTypeInfo(name, SbeTypeKind.ENUM, null, 0, encodingType, values, sbePackage, null);
  }
}
