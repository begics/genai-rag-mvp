package com.begics.rag.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderTextRedactorTest {
  private final ProviderTextRedactor redactor = new ProviderTextRedactor();

  @Test
  void masksEmailsPhonesAndValidPaymentCardNumbers() {
    String result = redactor.redact(
        "Contact orbit.synthetic@example.test. Reach at +41 79 123 45 67. Card: 4111 1111 1111 1111.");

    assertThat(result).isEqualTo(
        "Contact [REDACTED_EMAIL]. Reach at [REDACTED_PHONE]. Card: [REDACTED_PAYMENT_CARD].");
  }

  @Test
  void leavesOrdinaryIdentifiersAndInvalidCardNumbersUnchanged() {
    String text = "Project ORBIT-731, release 2024, and reference 4111 1111 1111 1112.";

    assertThat(redactor.redact(text)).isEqualTo(text);
  }

  @Test
  void handlesNullAndEmptyInput() {
    assertThat(redactor.redact(null)).isNull();
    assertThat(redactor.redact("")).isEmpty();
  }
}
