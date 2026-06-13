package io.scalecube.sbe.codegen.generator.model;

import java.util.List;

public class OperationDescriptor {
  public final String name;

  // ── request-reply / fireAndForget fields ──────────────────────────────────
  public final String command;              // nullable for subscription
  public final String reply;               // nullable for subscription
  public final String onError;             // nullable
  public final List<ErrorVariantDescriptor> errorVariants; // empty if none

  // ── subscription fields ───────────────────────────────────────────────────
  public final String topic;               // nullable for non-subscription
  // SBE message name decoded per buffer; nullable for non-subscription
  public final String event;

  public final String style; // requestOne | requestMany | fireAndForget | subscription

  public OperationDescriptor(
      String name,
      String command,
      String reply,
      String onError,
      List<ErrorVariantDescriptor> errorVariants,
      String topic,
      String event,
      String style) {
    this.name = name;
    this.command = command;
    this.reply = reply;
    this.onError = onError;
    this.errorVariants = errorVariants != null ? errorVariants : List.of();
    this.topic = topic;
    this.event = event;
    this.style = style;
  }

  public boolean isFireAndForget() {
    return "fireAndForget".equals(style);
  }

  public boolean isSubscription() {
    return "subscription".equals(style);
  }

  public boolean isRequestOne() {
    return "requestOne".equals(style);
  }

  public boolean isRequestMany() {
    return "requestMany".equals(style);
  }
}
