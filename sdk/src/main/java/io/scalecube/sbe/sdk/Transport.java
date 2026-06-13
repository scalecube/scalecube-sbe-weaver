package io.scalecube.sbe.sdk;

import org.agrona.DirectBuffer;
import reactor.core.publisher.Flux;

public interface Transport {

  /** Send a command and block until the reply arrives. */
  DirectBuffer request(DirectBuffer command, int offset, int length);

  /** Subscribe to a live-event topic; returns a cold or hot Flux of raw SBE frames. */
  Flux<DirectBuffer> subscribe(String topic);

  /** Send a command and receive a stream of reply frames (request-stream pattern). */
  Flux<DirectBuffer> requestMany(DirectBuffer command, int offset, int length);
}
