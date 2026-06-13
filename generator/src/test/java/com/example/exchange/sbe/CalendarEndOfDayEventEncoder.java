package com.example.exchange.sbe;

public final class CalendarEndOfDayEventEncoder {
  public static final int TEMPLATE_ID = 7;
  public static final int BLOCK_LENGTH = 8;

  public static int calendarIdId() { return 1; }
  public static String calendarIdMetaAttribute(MetaAttribute m) {
    return MetaAttribute.PRESENCE == m ? "required" : "";
  }
  public static int calendarIdNullValue() { return Integer.MIN_VALUE; }
  public static int calendarIdMinValue()  { return Integer.MIN_VALUE + 1; }
  public static int calendarIdMaxValue()  { return Integer.MAX_VALUE; }
  public CalendarEndOfDayEventEncoder calendarId(int value) { return this; }

  public static int epochDayId() { return 2; }
  public static String epochDayMetaAttribute(MetaAttribute m) {
    return MetaAttribute.PRESENCE == m ? "required" : "";
  }
  public static int epochDayNullValue() { return Integer.MIN_VALUE; }
  public static int epochDayMinValue()  { return Integer.MIN_VALUE + 1; }
  public static int epochDayMaxValue()  { return Integer.MAX_VALUE; }
  public CalendarEndOfDayEventEncoder epochDay(int value) { return this; }
}
