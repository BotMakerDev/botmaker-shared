// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A closed list of words or phrases found in a node's name as whole words, case ignored, any run of spaces between
 * a phrase's words: {@code "pay"} is in "Pay now", not in "PayPal" or "Repay".
 */
final class Words {

    private final List<String> words;
    private final List<Pattern> patterns;

    Words(List<String> words) {
        this.words = List.copyOf(words);
        this.patterns = this.words.stream().map(Words::pattern).toList();
    }

    /** The first of the list in {@code text}. */
    Optional<String> in(String text) {
        String said = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < words.size(); i++) {
            if (patterns.get(i).matcher(said).find()) {
                return Optional.of(words.get(i));
            }
        }
        return Optional.empty();
    }

    boolean any(String text) {
        return in(text).isPresent();
    }

    private static Pattern pattern(String word) {
        StringBuilder regex = new StringBuilder("(?<![\\p{L}\\p{N}])");
        String[] parts = word.toLowerCase(Locale.ROOT).strip().split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            regex.append(i == 0 ? "" : "\\s+").append(Pattern.quote(parts[i]));
        }
        return Pattern.compile(regex.append("(?![\\p{L}\\p{N}])").toString());
    }
}
