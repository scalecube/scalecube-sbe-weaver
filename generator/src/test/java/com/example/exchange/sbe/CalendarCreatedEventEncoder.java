package com.example.exchange.sbe;

public final class CalendarCreatedEventEncoder {
  public static final int TEMPLATE_ID = 2;
  public static final int BLOCK_LENGTH = 8;

  public static int calendarIdId() { return 1; }
  public static String calendarIdMetaAttribute(MetaAttribute m) {
    return MetaAttribute.PRESENCE == m ? "required" : "";
  }
  public static int calendarIdNullValue() { return Integer.MIN_VALUE; }
  public static int calendarIdMinValue()  { return Integer.MIN_VALUE + 1; }
  public static int calendarIdMaxValue()  { return Integer.MAX_VALUE; }
  public CalendarCreatedEventEncoder calendarId(int value) { return this; }

  public static int projectIdId() { return 2; }
  public static String projectIdMetaAttribute(MetaAttribute m) {
    return MetaAttribute.PRESENCE == m ? "required" : "";
  }
  public static int projectIdNullValue() { return Integer.MIN_VALUE; }
  public static int projectIdMinValue()  { return Integer.MIN_VALUE + 1; }
  public static int projectIdMaxValue()  { return Integer.MAX_VALUE; }
  public CalendarCreatedEventEncoder projectId(int value) { return this; }
}
