package com.begics.rag.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InvoiceExtraction(
    InvoiceField<String> supplier,
    InvoiceField<String> invoiceNumber,
    InvoiceField<LocalDate> invoiceDate,
    InvoiceField<LocalDate> dueDate,
    InvoiceField<String> currency,
    InvoiceField<BigDecimal> total) {}
