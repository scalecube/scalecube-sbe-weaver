package io.scalecube.sbe.codegen.generator.emit;

import io.scalecube.sbe.codegen.generator.model.SbeField;
import io.scalecube.sbe.codegen.generator.model.SbeSchema;
import io.scalecube.sbe.codegen.generator.model.SbeTypeInfo;
import io.scalecube.sbe.codegen.generator.model.SbeTypeKind;
import java.util.List;

public class TypeMapper {

  /** Public Java type for a message field (request builder / result POJO). */
  public static String publicJavaType(SbeField field, SbeSchema schema) {
    return resolveType(field.typeName, field.optional, schema);
  }

  /** Unboxed Java type (always primitive if possible) — used inside mappers. */
  public static String primitiveJavaType(SbeField field, SbeSchema schema) {
    return resolveType(field.typeName, false, schema);
  }

  private static String resolveType(String typeName, boolean optional, SbeSchema schema) {
    // Check if it's a named type in the schema
    SbeTypeInfo info = schema.types.get(typeName);
    if (info != null) {
      return switch (info.kind) {
        case PRIMITIVE  -> mapPrimitive(info.primitiveType, optional);
        case CHAR_ARRAY -> "String";
        case COMPOSITE  -> mapComposite(info.name);
        case ENUM       -> info.name;
      };
    }
    // Bare primitive type name (e.g. "int32" used directly on a field)
    return mapPrimitive(typeName, optional);
  }

  private static String mapComposite(String name) {
    return switch (name) {
      case "Decimal"           -> "java.math.BigDecimal";
      case "BigDecimal"        -> "java.math.BigDecimal";
      case "UUID"              -> "java.util.UUID";
      case "varStringEncoding" -> "String";
      default                  -> name;
    };
  }

  static String mapPrimitive(String primitiveType, boolean optional) {
    return switch (primitiveType) {
      case "int8"    -> optional ? "Byte"    : "byte";
      case "int16"   -> optional ? "Short"   : "short";
      case "int32"   -> optional ? "Integer" : "int";
      case "int64"   -> optional ? "Long"    : "long";
      case "uint8"   -> optional ? "Short"   : "short";
      case "uint16"  -> optional ? "Integer" : "int";
      case "uint32"  -> optional ? "Long"    : "long";
      case "uint64"  -> optional ? "Long"    : "long";
      case "boolean" -> optional ? "Boolean" : "boolean";
      case "char"    -> "String";
      default        -> "Object";
    };
  }

  /** Whether a field maps to a var-string (varStringEncoding). */
  public static boolean isVarString(SbeField field, SbeSchema schema) {
    SbeTypeInfo info = schema.types.get(field.typeName);
    return info != null && info.kind == SbeTypeKind.COMPOSITE
        && "varStringEncoding".equals(info.name);
  }

  /** Whether a field maps to the old SBE Decimal composite (mantissa/exponent) → BigDecimal. */
  public static boolean isDecimal(SbeField field, SbeSchema schema) {
    SbeTypeInfo info = schema.types.get(field.typeName);
    return info != null && info.kind == SbeTypeKind.COMPOSITE && "Decimal".equals(info.name);
  }

  /** Whether a field maps to the exchange BigDecimal composite (value/scale) → BigDecimal. */
  public static boolean isBigDecimalExchange(SbeField field, SbeSchema schema) {
    SbeTypeInfo info = schema.types.get(field.typeName);
    return info != null && info.kind == SbeTypeKind.COMPOSITE && "BigDecimal".equals(info.name);
  }

  /** Whether a field maps to the UUID composite → java.util.UUID. */
  public static boolean isUuid(SbeField field, SbeSchema schema) {
    SbeTypeInfo info = schema.types.get(field.typeName);
    return info != null && info.kind == SbeTypeKind.COMPOSITE && "UUID".equals(info.name);
  }

  /** Whether a field maps to a composite type with inspected sub-fields (Time, TradingDays, etc.).
   */
  public static boolean isCompositeWithFields(SbeField field, SbeSchema schema) {
    SbeTypeInfo info = schema.types.get(field.typeName);
    return info != null && info.kind == SbeTypeKind.COMPOSITE
        && info.compositeFields != null && !info.compositeFields.isEmpty();
  }

  /** Returns the sub-fields of a composite type, or empty list. */
  public static List<SbeTypeInfo.CompositeField> getCompositeFields(
      SbeField field, SbeSchema schema) {
    SbeTypeInfo info = schema.types.get(field.typeName);
    return (info != null && info.compositeFields != null) ? info.compositeFields : List.of();
  }

  /** Whether a field maps to a char-array → String. */
  public static boolean isCharArray(SbeField field, SbeSchema schema) {
    SbeTypeInfo info = schema.types.get(field.typeName);
    return info != null && info.kind == SbeTypeKind.CHAR_ARRAY;
  }

  /** Whether a field is an enum type. */
  public static boolean isEnum(SbeField field, SbeSchema schema) {
    SbeTypeInfo info = schema.types.get(field.typeName);
    return info != null && info.kind == SbeTypeKind.ENUM;
  }

  /** Strip trailing "Command" or "Event" to get the public class name. */
  public static String publicName(String messageName) {
    if (messageName.endsWith("Command")) {
      return messageName.substring(0, messageName.length() - "Command".length());
    }
    if (messageName.endsWith("Event")) {
      return messageName.substring(0, messageName.length() - "Event".length());
    }
    return messageName;
  }

  /** Capitalise first letter. */
  public static String capitalize(String s) {
    if (s == null || s.isEmpty()) {
      return s;
    }
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
