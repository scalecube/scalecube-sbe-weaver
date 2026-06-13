package com.example.exchange.sbe;

public final class MessageHeaderDecoder {
  public static final int ENCODED_LENGTH = 8;

  public MessageHeaderDecoder wrap(Object buf, int offset) { return this; }
  public int encodedLength() { return ENCODED_LENGTH; }
  public int templateId() { return 0; }
}
