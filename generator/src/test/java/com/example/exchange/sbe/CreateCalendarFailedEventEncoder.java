package com.example.exchange.sbe;

public final class CreateCalendarFailedEventEncoder {
  public static final int TEMPLATE_ID = 3;
  public static final int BLOCK_LENGTH = 1;

  public static int errorCodeId() { return 1; }
  public static String errorCodeMetaAttribute(MetaAttribute m) {
    return MetaAttribute.PRESENCE == m ? "required" : "";
  }
  public CreateCalendarFailedEventEncoder errorCode(ErrorCode value) { return this; }

  public static int errorMessageId() { return 201; }
  public static String errorMessageMetaAttribute(MetaAttribute m) {
    return MetaAttribute.PRESENCE == m ? "required" : "";
  }
  public static int errorMessageHeaderLength() { return 2; }
  public static String errorMessageCharacterEncoding() { return "US-ASCII"; }
  public CreateCalendarFailedEventEncoder errorMessage(String value) { return this; }
}
