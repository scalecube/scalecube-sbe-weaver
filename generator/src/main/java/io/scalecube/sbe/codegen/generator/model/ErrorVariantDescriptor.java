package io.scalecube.sbe.codegen.generator.model;

public class ErrorVariantDescriptor {

  public final String name;
  /**
   * {@code callbackError} — {@code void name(Consumer<Cmd>, Consumer<Err>)}
   * {@code callbackErrorWithRequest} — {@code void name(Consumer<Cmd>, BiConsumer<Cmd, Err>)}
   */
  public final String style;

  public ErrorVariantDescriptor(String name, String style) {
    this.name = name;
    this.style = style;
  }

  public boolean isWithRequest() {
    return "callbackErrorWithRequest".equals(style);
  }
}
