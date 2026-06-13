package com.example.order.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scalecube.sbe.sdk.Transport;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class OrderApiTest {

  private static final Transport FAKE = new Transport() {
    @Override
    public DirectBuffer request(DirectBuffer cmd, int offset, int length) {
      return new UnsafeBuffer(new byte[8]);
    }

    @Override
    public Flux<DirectBuffer> subscribe(String topic) {
      return Flux.empty();
    }

    @Override
    public Flux<DirectBuffer> requestMany(DirectBuffer cmd, int offset, int length) {
      return Flux.empty();
    }
  };

  // ── placeOrder ─────────────────────────────────────────────────────────────

  @Test
  void placeOrder() {
    var api = new OrderApiImpl(FAKE);
    assertThatThrownBy(() -> api.placeOrder(req -> {}))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void placeOrderAsync() {
    var api = new OrderApiImpl(FAKE);
    assertThat(api.placeOrderAsync(req -> {})).isNotNull();
  }

  @Test
  void placeOrderUnsuccessfully() {
    var api = new OrderApiImpl(FAKE);
    assertThatThrownBy(() -> api.placeOrderUnsuccessfully(req -> {}, (req, err) -> {}))
        .isInstanceOf(RuntimeException.class);
  }

  // ── cancelOrder ────────────────────────────────────────────────────────────

  @Test
  void cancelOrder() {
    var api = new OrderApiImpl(FAKE);
    assertThatThrownBy(() -> api.cancelOrder(req -> {}))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void cancelOrderAsync() {
    var api = new OrderApiImpl(FAKE);
    assertThat(api.cancelOrderAsync(req -> {})).isNotNull();
  }

  @Test
  void cancelOrderUnsuccessfully() {
    var api = new OrderApiImpl(FAKE);
    assertThatThrownBy(() -> api.cancelOrderUnsuccessfully(req -> {}, err -> {}))
        .isInstanceOf(RuntimeException.class);
  }

  // ── modifyOrder ────────────────────────────────────────────────────────────

  @Test
  void modifyOrder() {
    var api = new OrderApiImpl(FAKE);
    assertThatThrownBy(() -> api.modifyOrder(req -> {}))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void modifyOrderAsync() {
    var api = new OrderApiImpl(FAKE);
    assertThat(api.modifyOrderAsync(req -> {})).isNotNull();
  }

  // ── subscriptions ──────────────────────────────────────────────────────────

  @Test
  void subscribeOrderPlaced() {
    var api = new OrderApiImpl(FAKE);
    assertThat(api.subscribeOrderPlaced()).isNotNull();
  }

  @Test
  void subscribeOrderCancelled() {
    var api = new OrderApiImpl(FAKE);
    assertThat(api.subscribeOrderCancelled()).isNotNull();
  }

  @Test
  void subscribeOrderExecuted() {
    var api = new OrderApiImpl(FAKE);
    assertThat(api.subscribeOrderExecuted()).isNotNull();
  }

  // ── sendHeartbeat ──────────────────────────────────────────────────────────

  @Test
  void sendHeartbeat() {
    var api = new OrderApiImpl(FAKE);
    api.sendHeartbeat(req -> {});
  }

  @Test
  void sendHeartbeatAsync() {
    var api = new OrderApiImpl(FAKE);
    assertThat(api.sendHeartbeatAsync(req -> {})).isNotNull();
  }

  @Test
  void getOrderHistory() {
    var api = new OrderApiImpl(FAKE);
    assertThat(api.getOrderHistory(req -> {})).isNotNull();
  }

  @Test
  void subscribeOrdersByInstrument() {
    var api = new OrderApiImpl(FAKE);
    assertThat(api.subscribeOrdersByInstrument(req -> {})).isNotNull();
  }
}
