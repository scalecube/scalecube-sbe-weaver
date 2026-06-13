package io.scalecube.sbe.sdk;

public abstract class SdkException extends RuntimeException {

  protected SdkException(String message) {
    super(message);
  }
}
