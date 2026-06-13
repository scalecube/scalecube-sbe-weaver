package com.example.exchange.sbe;

public final class CreateCalendarCommandEncoder {
  public static final int TEMPLATE_ID = 1;
  public static final int BLOCK_LENGTH = 10;

  // projectId — required int32
  public static int projectIdId() { return 1; }
  public static String projectIdMetaAttribute(MetaAttribute m) {
    return MetaAttribute.PRESENCE == m ? "required" : "";
  }
  public static int projectIdNullValue() { return Integer.MIN_VALUE; }
  public static int projectIdMinValue()  { return Integer.MIN_VALUE + 1; }
  public static int projectIdMaxValue()  { return Integer.MAX_VALUE; }
  public CreateCalendarCommandEncoder projectId(int value) { return this; }

  // timeZoneOffsetMinutes — optional int16
  public static int timeZoneOffsetMinutesId() { return 2; }
  public static String timeZoneOffsetMinutesMetaAttribute(MetaAttribute m) {
    return MetaAttribute.PRESENCE == m ? "optional" : "";
  }
  public static short timeZoneOffsetMinutesNullValue() { return Short.MIN_VALUE; }
  public static short timeZoneOffsetMinutesMinValue()  { return Short.MIN_VALUE + 1; }
  public static short timeZoneOffsetMinutesMaxValue()  { return Short.MAX_VALUE; }
  public CreateCalendarCommandEncoder timeZoneOffsetMinutes(short value) { return this; }

  // name — charArray
  public static int nameId() { return 3; }
  public static String nameMetaAttribute(MetaAttribute m) {
    return MetaAttribute.PRESENCE == m ? "required" : "";
  }
  public static int nameLength() { return 16; }
  public static byte nameNullValue() { return 0; }
  public static byte nameMinValue()  { return 32; }
  public static byte nameMaxValue()  { return 126; }
  public static String nameCharacterEncoding() { return "US-ASCII"; }
  public CreateCalendarCommandEncoder name(String src)      { return this; }
  public CreateCalendarCommandEncoder name(CharSequence src){ return this; }

  // fee — composite (DecimalEncoder)
  public static int feeId() { return 4; }
  private final DecimalEncoder feeEnc = new DecimalEncoder();
  public DecimalEncoder fee() { return feeEnc; }

  // holidays — group
  public static int holidaysId() { return 10; }

  public static final class HolidaysEncoder {
    public static int epochDayId() { return 11; }
    public static String epochDayMetaAttribute(MetaAttribute m) {
      return MetaAttribute.PRESENCE == m ? "required" : "";
    }
    public static int epochDayNullValue() { return Integer.MIN_VALUE; }
    public static int epochDayMinValue()  { return Integer.MIN_VALUE + 1; }
    public static int epochDayMaxValue()  { return Integer.MAX_VALUE; }
    public HolidaysEncoder epochDay(int value) { return this; }
    public HolidaysEncoder next() { return this; }
  }

  private final HolidaysEncoder holidaysEnc = new HolidaysEncoder();
  public HolidaysEncoder holidaysCount(int count) { return holidaysEnc; }

  // userId — varData
  public static int userIdId() { return 201; }
  public static String userIdMetaAttribute(MetaAttribute m) {
    return MetaAttribute.PRESENCE == m ? "required" : "";
  }
  public static int userIdHeaderLength() { return 2; }
  public static String userIdCharacterEncoding() { return "US-ASCII"; }
  public CreateCalendarCommandEncoder userId(String value) { return this; }
}
