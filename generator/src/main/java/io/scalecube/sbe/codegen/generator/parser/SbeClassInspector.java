package io.scalecube.sbe.codegen.generator.parser;

import io.scalecube.sbe.codegen.generator.model.SbeEnumValue;
import io.scalecube.sbe.codegen.generator.model.SbeField;
import io.scalecube.sbe.codegen.generator.model.SbeGroup;
import io.scalecube.sbe.codegen.generator.model.SbeMessage;
import io.scalecube.sbe.codegen.generator.model.SbeSchema;
import io.scalecube.sbe.codegen.generator.model.SbeTypeInfo;
import io.scalecube.sbe.codegen.generator.model.SbeVarData;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * Builds an SbeSchema by inspecting SBE-generated encoder classes already on the classpath.
 * The first argument is a root Java package prefix — all sub-packages are scanned recursively.
 *
 * <p>Relies on structural patterns of SBE-generated encoders:
 * <ul>
 *   <li>Message encoders have a static {@code TEMPLATE_ID} field.
 *   <li>Every field has a static {@code fieldNameId()} method (used for ID-based ordering).
 *   <li>VarData fields additionally have {@code fieldNameHeaderLength()}.
 *   <li>Group fields additionally have {@code fieldNameCount(int)} instance method.
 *   <li>CharArray fields additionally have a static {@code fieldNameLength()}.
 *   <li>Composite fields have a no-arg instance {@code fieldName()} returning an encoder class.
 *   <li>Primitive/enum fields have a single-arg setter {@code fieldName(TYPE)}.
 * </ul>
 */
public class SbeClassInspector {

  private static final String CHAR_ARRAY_TYPE = "charArray";

  public SbeSchema inspect(String rootPackage) throws Exception {
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    List<Class<?>> classes = scanPackage(rootPackage, cl);

    Map<String, SbeTypeInfo> types = new LinkedHashMap<>();
    Map<String, SbeMessage> messages = new LinkedHashMap<>();

    // Pass 1: enums and composite encoders (no TEMPLATE_ID)
    for (Class<?> cls : classes) {
      if (cls.isEnum()) {
        SbeTypeInfo t = parseEnum(cls);
        types.put(t.name, t);
      } else if (isCompositeEncoder(cls)) {
        SbeTypeInfo t = parseComposite(cls, cl);
        types.put(t.name, t);
      }
    }

    // Pass 2: message encoders (have TEMPLATE_ID)
    for (Class<?> cls : classes) {
      if (isMessageEncoder(cls)) {
        SbeMessage msg = parseMessage(cls, types, cl);
        messages.put(msg.name, msg);
      }
    }

    // schema.packageName = package of MessageHeaderEncoder (needed for header imports)
    String headerPackage = classes.stream()
        .filter(c -> "MessageHeaderEncoder".equals(c.getSimpleName()))
        .map(c -> c.getPackage().getName())
        .findFirst()
        .orElse(rootPackage);

    return new SbeSchema(headerPackage, 0, types, messages);
  }

  // ── classpath scanning ──────────────────────────────────────────────────

  private List<Class<?>> scanPackage(String rootPackage, ClassLoader cl) {
    String rootPath = rootPackage.replace('.', '/');
    List<Class<?>> result = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();

    for (File f : collectClasspathEntries(cl)) {
      if (f.isDirectory()) {
        File pkgDir = new File(f, rootPath.replace('/', File.separatorChar));
        if (pkgDir.isDirectory()) {
          scanDirRecursive(pkgDir, rootPackage, cl, result, seen);
        }
      } else if (f.isFile()) {
        String name = f.getName();
        if (name.endsWith(".jar") || name.endsWith(".zip")) {
          scanJar(f, rootPath, cl, result, seen);
        }
      }
    }
    return result;
  }

  private List<File> collectClasspathEntries(ClassLoader cl) {
    Set<String> seen = new LinkedHashSet<>();
    List<File> files = new ArrayList<>();

    // Walk the classloader hierarchy collecting URLClassLoader URLs
    for (ClassLoader c = cl; c != null; c = c.getParent()) {
      if (c instanceof URLClassLoader) {
        for (URL url : ((URLClassLoader) c).getURLs()) {
          if ("file".equals(url.getProtocol())) {
            try {
              String path = new File(url.toURI()).getCanonicalPath();
              if (seen.add(path)) {
                files.add(new File(path));
              }
            } catch (Exception ignored) { // ignore
            }
          }
        }
      }
    }

    // Fallback: also include java.class.path entries not already found
    String cp = System.getProperty("java.class.path");
    if (cp != null) {
      for (String entry : cp.split(File.pathSeparator)) {
        try {
          String path = new File(entry).getCanonicalPath();
          if (seen.add(path)) {
            files.add(new File(path));
          }
        } catch (Exception ignored) { // ignore
        }
      }
    }
    return files;
  }

  private void scanDirRecursive(File dir, String pkg, ClassLoader cl,
                                 List<Class<?>> result, Set<String> seen) {
    File[] files = dir.listFiles();
    if (files == null) {
      return;
    }
    for (File f : files) {
      if (f.isDirectory()) {
        scanDirRecursive(f, pkg + "." + f.getName(), cl, result, seen);
      } else if (f.getName().endsWith(".class") && !f.getName().contains("$")) {
        String className = pkg + "." + f.getName().replace(".class", "");
        loadIfAbsent(className, cl, result, seen);
      }
    }
  }

  private void scanJar(File jarFile, String rootPath, ClassLoader cl,
                        List<Class<?>> result, Set<String> seen) {
    try (JarFile jar = new JarFile(jarFile)) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        String name = entry.getName();
        if (name.startsWith(rootPath + "/") && name.endsWith(".class") && !name.contains("$")) {
          String className = name.replace('/', '.').replace(".class", "");
          loadIfAbsent(className, cl, result, seen);
        }
      }
    } catch (Exception ignored) {
      // skip unreadable JARs
    }
  }

  private void loadIfAbsent(String className, ClassLoader cl,
                              List<Class<?>> result, Set<String> seen) {
    if (!seen.add(className)) {
      return;
    }
    try {
      result.add(Class.forName(className, false, cl));
    } catch (Throwable ignored) {
      // skip classes whose dependencies aren't on the classpath
    }
  }

  // ── classification ──────────────────────────────────────────────────────

  private boolean isMessageEncoder(Class<?> cls) {
    try {
      cls.getField("TEMPLATE_ID");
      return true;
    } catch (NoSuchFieldException e) {
      return false;
    }
  }

  private boolean isCompositeEncoder(Class<?> cls) {
    if (!cls.getSimpleName().endsWith("Encoder")) {
      return false;
    }
    if (isMessageEncoder(cls)) {
      return false;
    }
    String n = cls.getSimpleName();
    // Exclude SBE infrastructure encoder classes
    return !n.equals("MessageHeaderEncoder")
        && !n.equals("GroupSizeEncodingEncoder")
        && !n.equals("StringEncoder")     // SBE varString infra → would shadow java.lang.String
        && !n.equals("VarDataEncoder");   // SBE varString infra
  }

  // ── enum ────────────────────────────────────────────────────────────────

  private SbeTypeInfo parseEnum(Class<?> cls) {
    List<SbeEnumValue> values = new ArrayList<>();
    for (Object c : cls.getEnumConstants()) {
      String n = ((Enum<?>) c).name();
      if (!"NULL_VAL".equals(n) && !"NullVal".equals(n)) {
        values.add(new SbeEnumValue(n, String.valueOf(((Enum<?>) c).ordinal())));
      }
    }
    return SbeTypeInfo.enumType(cls.getSimpleName(), null, values, cls.getPackage().getName());
  }

  // ── composite ───────────────────────────────────────────────────────────

  private SbeTypeInfo parseComposite(Class<?> encoderCls, ClassLoader cl) {
    String typeName = encoderCls.getSimpleName().replace("Encoder", "");
    String pkg = encoderCls.getPackage().getName();

    // UUID and BigDecimal/Decimal map to Java stdlib — no sub-fields needed
    if ("UUID".equals(typeName) || "BigDecimal".equals(typeName) || "Decimal".equals(typeName)) {
      return SbeTypeInfo.composite(typeName, pkg);
    }

    // Load the corresponding Decoder class and collect its value getter methods
    String decoderFqn = encoderCls.getName().replace("Encoder", "Decoder");
    Class<?> decoderCls = null;
    try {
      decoderCls = cl.loadClass(decoderFqn);
    } catch (Exception ignored) { // ignore
    }

    if (decoderCls == null) {
      return SbeTypeInfo.composite(typeName, pkg);
    }

    List<SbeTypeInfo.CompositeField> fields = new ArrayList<>();
    for (Method m : decoderCls.getMethods()) {
      if (Modifier.isStatic(m.getModifiers())) {
        continue;
      }
      if (m.getParameterCount() != 0) {
        continue;
      }
      if (!isPrimitiveOrBoolean(m.getReturnType())) {
        continue;
      }
      if (isInfraMethod(m.getName())) {
        continue;
      }
      fields.add(new SbeTypeInfo.CompositeField(m.getName(),
          javaTypeToSbeName(m.getReturnType())));
    }
    fields.sort(Comparator.comparing(SbeTypeInfo.CompositeField::name));

    return fields.isEmpty()
        ? SbeTypeInfo.composite(typeName, pkg)
        : SbeTypeInfo.compositeWithFields(typeName, pkg, fields);
  }

  private static boolean isPrimitiveOrBoolean(Class<?> t) {
    return t == int.class || t == long.class || t == short.class || t == byte.class
        || t == float.class || t == double.class || t == boolean.class || t == char.class;
  }

  private static boolean isInfraMethod(String name) {
    return name.equals("encodedLength") || name.equals("offset") || name.equals("sbeSchemaId")
        || name.equals("sbeSchemaVersion") || name.equals("sbeType") || name.equals("hashCode")
        || name.equals("toString") || name.equals("getClass") || name.equals("isEmpty")
        || name.equals("wait") || name.equals("notify") || name.equals("notifyAll")
        || name.equals("equals") || name.equals("appendTo") || name.equals("buffer")
        || name.equals("limit") || name.equals("actingVersion") || name.equals("wrap")
        || name.equals("getRaw") || name.equals("setRaw");  // SBE set-type infra
  }

  // ── message ─────────────────────────────────────────────────────────────

  private SbeMessage parseMessage(Class<?> cls, Map<String, SbeTypeInfo> types,
                                   ClassLoader cl) throws Exception {
    String msgName = cls.getSimpleName().replace("Encoder", "");
    String sbePackage = cls.getPackage().getName();

    // Collect field names sorted by SBE field ID
    List<Map.Entry<String, Integer>> orderedFields = collectFieldIds(cls);

    List<SbeField>   fields  = new ArrayList<>();
    List<SbeGroup>   groups  = new ArrayList<>();
    List<SbeVarData> varData = new ArrayList<>();

    for (Map.Entry<String, Integer> entry : orderedFields) {
      String fieldName = entry.getKey();
      int    fieldId   = entry.getValue();

      if (hasStaticNoArgMethod(cls, fieldName + "HeaderLength")) {
        varData.add(new SbeVarData(fieldName, fieldId, "varString"));

      } else if (hasInstanceMethod(cls, fieldName + "Count", int.class)) {
        Class<?> innerCls = findInnerEncoder(cls, capitalize(fieldName) + "Encoder");
        List<SbeField> groupFields = innerCls != null ? parseGroupFields(innerCls) : List.of();
        groups.add(new SbeGroup(fieldName, fieldId, groupFields));

      } else if (hasStaticNoArgMethod(cls, fieldName + "Length")) {
        types.putIfAbsent(CHAR_ARRAY_TYPE, SbeTypeInfo.charArray(CHAR_ARRAY_TYPE, 0));
        fields.add(new SbeField(fieldName, fieldId, CHAR_ARRAY_TYPE, false));

      } else {
        Method getter = findNoArgInstanceMethod(cls, fieldName);
        if (getter != null && getter.getReturnType().getSimpleName().endsWith("Encoder")) {
          // composite
          String typeName = getter.getReturnType().getSimpleName().replace("Encoder", "");
          types.putIfAbsent(typeName,
              SbeTypeInfo.composite(typeName, getter.getReturnType().getPackage().getName()));
          fields.add(new SbeField(fieldName, fieldId, typeName, false));
        } else {
          // primitive or enum
          String  typeName = resolveFieldType(cls, fieldName);
          boolean optional = isOptional(cls, fieldName, sbePackage, cl);
          fields.add(new SbeField(fieldName, fieldId, typeName, optional));
        }
      }
    }

    return new SbeMessage(msgName, 0, fields, groups, varData, sbePackage);
  }

  private List<SbeField> parseGroupFields(Class<?> innerCls) throws Exception {
    List<SbeField> fields = new ArrayList<>();
    for (Map.Entry<String, Integer> e : collectFieldIds(innerCls)) {
      String fn     = e.getKey();
      int    fid    = e.getValue();
      String typeName = resolveFieldType(innerCls, fn);
      fields.add(new SbeField(fn, fid, typeName, false));
    }
    return fields;
  }

  private List<Map.Entry<String, Integer>> collectFieldIds(Class<?> cls) throws Exception {
    Map<String, Integer> ids = new LinkedHashMap<>();
    for (Method m : cls.getMethods()) {
      if (!Modifier.isStatic(m.getModifiers())) {
        continue;
      }
      if (m.getReturnType() != int.class) {
        continue;
      }
      if (m.getParameterCount() != 0) {
        continue;
      }
      String methodName = m.getName();
      if (!methodName.endsWith("Id") || methodName.length() <= 2) {
        continue;
      }
      String fieldName = methodName.substring(0, methodName.length() - 2);
      if (fieldName.isEmpty()) {
        continue;
      }
      ids.put(fieldName, (int) m.invoke(null));
    }
    return ids.entrySet().stream()
        .sorted(Map.Entry.comparingByValue())
        .collect(Collectors.toList());
  }

  // ── type resolution ─────────────────────────────────────────────────────

  private String resolveFieldType(Class<?> cls, String fieldName) {
    for (Method m : cls.getMethods()) {
      if (!m.getName().equals(fieldName)) {
        continue;
      }
      if (Modifier.isStatic(m.getModifiers())) {
        continue;
      }
      if (m.getParameterCount() != 1) {
        continue;
      }
      Class<?> param = m.getParameterTypes()[0];
      if (param == String.class || param == CharSequence.class) {
        continue;
      }
      if (param == byte[].class) {
        continue;
      }
      return javaTypeToSbeName(param);
    }
    return "int32";
  }

  private String javaTypeToSbeName(Class<?> javaType) {
    if (javaType == int.class) {
      return "int32";
    }
    if (javaType == long.class) {
      return "int64";
    }
    if (javaType == short.class) {
      return "int16";
    }
    if (javaType == byte.class) {
      return "int8";
    }
    if (javaType == float.class) {
      return "float";
    }
    if (javaType == double.class) {
      return "double";
    }
    if (javaType == boolean.class) {
      return "boolean";
    }
    if (javaType == char.class) {
      return "char";
    }
    if (javaType.isEnum()) {
      return javaType.getSimpleName();
    }
    return "int32";
  }

  private boolean isOptional(Class<?> cls, String fieldName, String sbePackage, ClassLoader cl) {
    try {
      Class<?> metaAttrClass = cl.loadClass(sbePackage + ".MetaAttribute");
      Object presenceVal = null;
      for (Object c : metaAttrClass.getEnumConstants()) {
        if ("PRESENCE".equals(((Enum<?>) c).name())) {
          presenceVal = c;
          break;
        }
      }
      if (presenceVal == null) {
        return false;
      }
      Method m = cls.getMethod(fieldName + "MetaAttribute", metaAttrClass);
      String result = (String) m.invoke(null, presenceVal);
      return "optional".equals(result);
    } catch (Exception e) {
      return false;
    }
  }

  // ── reflection helpers ──────────────────────────────────────────────────

  private boolean hasStaticNoArgMethod(Class<?> cls, String name) {
    try {
      return Modifier.isStatic(cls.getMethod(name).getModifiers());
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  private boolean hasInstanceMethod(Class<?> cls, String name, Class<?>... params) {
    try {
      return !Modifier.isStatic(cls.getMethod(name, params).getModifiers());
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  private Method findNoArgInstanceMethod(Class<?> cls, String name) {
    try {
      Method m = cls.getMethod(name);
      return Modifier.isStatic(m.getModifiers()) ? null : m;
    } catch (NoSuchMethodException e) {
      return null;
    }
  }

  private Class<?> findInnerEncoder(Class<?> cls, String simpleName) {
    for (Class<?> inner : cls.getDeclaredClasses()) {
      if (inner.getSimpleName().equals(simpleName)) {
        return inner;
      }
    }
    return null;
  }

  private String capitalize(String s) {
    if (s == null || s.isEmpty()) {
      return s;
    }
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
