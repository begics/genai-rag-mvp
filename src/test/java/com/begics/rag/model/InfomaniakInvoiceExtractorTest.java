package com.begics.rag.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class InfomaniakInvoiceExtractorTest {
  private static final String SOURCE =
      "ACME GmbH invoice INV-123 issued 2026-03-01 due 2026-03-31 total CHF 120.50";

  @Test
  void parsesTypedInvoiceFieldsAndKeepsSourceEvidence() {
    InfomaniakInvoiceExtractor extractor = extractor("""
        {
          "supplier":{"value":"ACME GmbH","evidence":"ACME GmbH"},
          "invoiceNumber":{"value":"INV-123","evidence":"INV-123"},
          "invoiceDate":{"value":"2026-03-01","evidence":"2026-03-01"},
          "dueDate":{"value":"2026-03-31","evidence":"2026-03-31"},
          "currency":{"value":"CHF","evidence":"CHF 120.50"},
          "total":{"value":"120.50","evidence":"CHF 120.50"}
        }
        """);

    var result = extractor.extract(SOURCE);

    assertThat(result.supplier().value()).isEqualTo("ACME GmbH");
    assertThat(result.invoiceNumber().value()).isEqualTo("INV-123");
    assertThat(result.invoiceDate().value()).hasToString("2026-03-01");
    assertThat(result.dueDate().value()).hasToString("2026-03-31");
    assertThat(result.currency().value()).isEqualTo("CHF");
    assertThat(result.total().value()).hasToString("120.50");
    assertThat(result.total().evidence()).isEqualTo("CHF 120.50");
  }

  @Test
  void requestsStrictlyTypedJsonSchemaOutputFromTheProvider() {
    AtomicReference<String> requestedSchema = new AtomicReference<>();
    StructuredChatModel chat = new StructuredChatModel() {
      @Override
      public String answer(String question, String context) {
        throw new AssertionError("Invoice extraction must use schema-constrained output");
      }

      @Override
      public String answerWithSchema(String instructions, String context, String jsonSchema) {
        requestedSchema.set(jsonSchema);
        return """
            {
              "supplier":{"value":"ACME GmbH","evidence":"ACME GmbH"},
              "invoiceNumber":{"value":"INV-123","evidence":"INV-123"},
              "invoiceDate":{"value":"2026-03-01","evidence":"2026-03-01"},
              "dueDate":{"value":"2026-03-31","evidence":"2026-03-31"},
              "currency":{"value":"CHF","evidence":"CHF 120.50"},
              "total":{"value":"120.50","evidence":"CHF 120.50"}
            }
            """;
      }
    };

    new InfomaniakInvoiceExtractor(chat, JsonMapper.builder().build()).extract(SOURCE);

    assertThat(requestedSchema.get())
        .contains("\"type\":\"object\"")
        .contains("\"required\":[\"supplier\",\"invoiceNumber\",\"invoiceDate\",\"dueDate\",\"currency\",\"total\"]")
        .contains("\"additionalProperties\":false")
        .contains("\"pattern\":\"^[A-Z]{3}$\"");
  }

  @Test
  void preservesExplicitMissingValues() {
    InfomaniakInvoiceExtractor extractor = extractor("""
        {
          "supplier":{"value":null,"evidence":null},
          "invoiceNumber":{"value":null,"evidence":null},
          "invoiceDate":{"value":null,"evidence":null},
          "dueDate":{"value":null,"evidence":null},
          "currency":{"value":null,"evidence":null},
          "total":{"value":null,"evidence":null}
        }
        """);

    var result = extractor.extract(SOURCE);

    assertThat(result.supplier().value()).isNull();
    assertThat(result.supplier().evidence()).isNull();
    assertThat(result.total().value()).isNull();
  }

  @Test
  void rejectsEvidenceThatIsNotPresentInTheDocument() {
    InfomaniakInvoiceExtractor extractor = extractor("""
        {
          "supplier":{"value":"ACME GmbH","evidence":"invented source"},
          "invoiceNumber":{"value":null,"evidence":null},
          "invoiceDate":{"value":null,"evidence":null},
          "dueDate":{"value":null,"evidence":null},
          "currency":{"value":null,"evidence":null},
          "total":{"value":null,"evidence":null}
        }
        """);

    assertThatThrownBy(() -> extractor.extract(SOURCE))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("failed validation");
  }

  @Test
  void rejectsMalformedProviderOutput() {
    InfomaniakInvoiceExtractor extractor = extractor("This is not JSON");

    assertThatThrownBy(() -> extractor.extract(SOURCE))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("failed validation");
  }

  private static InfomaniakInvoiceExtractor extractor(String response) {
    StructuredChatModel chat = new StructuredChatModel() {
      @Override
      public String answer(String question, String context) {
        return response;
      }

      @Override
      public String answerWithSchema(String instructions, String context, String jsonSchema) {
        return response;
      }
    };
    return new InfomaniakInvoiceExtractor(chat, JsonMapper.builder().build());
  }
}
