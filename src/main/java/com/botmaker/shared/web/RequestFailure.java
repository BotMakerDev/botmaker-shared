// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

/**
 * A request of the page that failed: answered 4xx or 5xx, or never answered.
 *
 * @param url    what was asked for
 * @param status the HTTP status, {@code 0} when there was no answer
 * @param error  Chromium's reason when there was no answer ({@code net::ERR_NAME_NOT_RESOLVED}), else {@code ""}
 */
public record RequestFailure(String url, int status, String error) {

    public RequestFailure {
        url = url == null ? "" : url;
        error = error == null ? "" : error;
    }
}
