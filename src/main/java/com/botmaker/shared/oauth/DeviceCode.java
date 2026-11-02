package com.botmaker.shared.oauth;

/**
 * A device-code response the UI shows the user: the code to type and the page to type it on.
 *
 * @param deviceCode       the code the poll sends back; never shown
 * @param userCode         the one-time code the user enters in the browser
 * @param verificationUri  the page to enter it on (Google names it {@code verification_url})
 * @param intervalSeconds  how long to wait between polls
 * @param expiresInSeconds how long the code lives
 */
public record DeviceCode(String deviceCode, String userCode, String verificationUri,
                         int intervalSeconds, int expiresInSeconds) {}
