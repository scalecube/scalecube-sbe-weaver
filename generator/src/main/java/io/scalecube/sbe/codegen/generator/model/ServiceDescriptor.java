package io.scalecube.sbe.codegen.generator.model;

import java.util.List;

public class ServiceDescriptor {
  public final String serviceName;
  public final String packageName;
  public final List<OperationDescriptor> operations;

  public ServiceDescriptor(
      String serviceName, String packageName, List<OperationDescriptor> operations) {
    this.serviceName = serviceName;
    this.packageName = packageName;
    this.operations = operations;
  }
}
