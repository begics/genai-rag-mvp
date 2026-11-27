package com.begics.rag.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProviderTextRedactor {
  private static final Pattern EMAIL = Pattern.compile(
      "(?i)(?<![\\w.+-])[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}(?![\\w-])");
  private static final Pattern PHONE = Pattern.compile(
      "(?<!\\w)(?:\\+\\d{1,3}[ .-]?)?(?:\\(?\\d{2,4}\\)?[ .-]){1,3}\\d{2,4}(?!\\w)");
  private static final Pattern CARD = Pattern.compile(
      "(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)");

  public String redact(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String withoutCards = replaceCards(text);
    String redacted = replace(EMAIL, withoutCards, "[REDACTED_EMAIL]");
    return replacePhones(redacted);
  }

  private static String replaceCards(String text) {
    Matcher cards = CARD.matcher(text);
    StringBuffer result = new StringBuffer();
    while (cards.find()) {
      String candidate = cards.group();
      cards.appendReplacement(result, Matcher.quoteReplacement(
          passesLuhn(candidate) ? "[REDACTED_PAYMENT_CARD]" : candidate));
    }
    cards.appendTail(result);
    return result.toString();
  }

  private static String replacePhones(String text) {
    Matcher phones = PHONE.matcher(text);
    StringBuffer result = new StringBuffer();
    while (phones.find()) {
      String candidate = phones.group();
      long digitCount = candidate.chars().filter(Character::isDigit).count();
      phones.appendReplacement(result, Matcher.quoteReplacement(
          digitCount >= 7 && digitCount <= 15 ? "[REDACTED_PHONE]" : candidate));
    }
    phones.appendTail(result);
    return result.toString();
  }

  private static String replace(Pattern pattern, String text, String replacement) {
    return pattern.matcher(text).replaceAll(Matcher.quoteReplacement(replacement));
  }

  private static boolean passesLuhn(String value) {
    int sum = 0;
    boolean doubleDigit = false;
    for (int i = value.length() - 1; i >= 0; i--) {
      char character = value.charAt(i);
      if (character == ' ' || character == '-') {
        continue;
      }
      int digit = character - '0';
      if (doubleDigit) {
        digit *= 2;
        if (digit > 9) {
          digit -= 9;
        }
      }
      sum += digit;
      doubleDigit = !doubleDigit;
    }
    return sum % 10 == 0;
  }
}
