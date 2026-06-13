package com.example.exchange.sbe;

public final class CalendarUpdatedEventEncoder {
  public static final int TEMPLATE_ID = 5;
  public static final int BLOCK_LENGTH = 4;

  public static int calendarIdId() { return 1; }
  public static String calendarIdMetaAttribute(MetaAttribute m) {
    return MetaAttribute.PRESENCE == m ? "required" : "";
  }
  public static int calendarIdNullValue() { return Integer.MIN_VALUE; }
  public static int calendarIdMinValue()  { return Integer.MIN_VALUE + 1; }
  public static int calendarIdMaxValue()  { return Integer.MAX_VALUE; }
  public CalendarUpdatedEventEncoder calendarId(int value) { return this; }
}
