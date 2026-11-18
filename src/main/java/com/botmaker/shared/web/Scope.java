// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import crawlercommons.domains.EffectiveTldFinder;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * <b>The site a discovery stays on</b> (spec {@code 2026-11-18-web-discovery-design.md} §3): the start URL's
 * registrable domain by the public suffix list crawler-commons ships ({@code app.nunu.ai} → {@code nunu.ai},
 * {@code a.b.co.uk} → {@code b.co.uk}; a private suffix such as {@code github.io} counts, so one Pages site is not
 * every one), and every subdomain of it. Scheme and port decide nothing. An IP address, and a host that is itself a
 * public suffix, are their own scope alone; {@code localhost} and its subdomains are {@code localhost}; a suffix the
 * list does not know ({@code .test}) leaves the last two labels.
 *
 * @param domain the registrable domain, lower case; for an exact scope, the host
 * @param exact  only {@code domain} itself, no subdomain
 */
public record Scope(String domain, boolean exact) {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    public Scope {
        domain = host(domain);
    }

    /** The scope of a discovery started at {@code start}. */
    public static Scope of(URI start) {
        String host = host(start.getHost());
        if (host.isEmpty()) {
            throw new IllegalArgumentException("no host in " + start);
        }
        if (ip(host) || suffix(host)) {
            return new Scope(host, true);
        }
        return new Scope(registrable(host), false);
    }

    /** Whether {@code url} is on the site: its host is the domain or ends with {@code .} and the domain. */
    public boolean contains(URI url) {
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return false;
        }
        String host = host(url.getHost());
        if (host.isEmpty()) {
            return false;
        }
        return host.equals(domain) || !exact && host.endsWith("." + domain);
    }

    /** {@link #contains(URI)} of a URL as text; {@code false} for one that does not parse. */
    public boolean contains(String url) {
        try {
            return contains(URI.create(url));
        } catch (IllegalArgumentException notAUrl) {
            return false;
        }
    }

    static String registrable(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            return "localhost";
        }
        String listed = EffectiveTldFinder.getAssignedDomain(host, true, false);
        if (listed != null && !listed.isEmpty()) {
            return listed;
        }
        String[] labels = host.split("\\.");
        return labels.length <= 2 ? host : labels[labels.length - 2] + "." + labels[labels.length - 1];
    }

    /** A host the list names as a suffix itself ({@code github.io}, {@code co.uk}): nobody's site but its own. */
    private static boolean suffix(String host) {
        EffectiveTldFinder.EffectiveTLD tld = EffectiveTldFinder.getEffectiveTLD(host, false);
        return tld != null && host.equals(tld.getDomain());
    }

    private static boolean ip(String host) {
        return host.contains(":") || IPV4.matcher(host).matches();
    }

    private static String host(String host) {
        if (host == null) {
            return "";
        }
        String clean = host.strip().toLowerCase(Locale.ROOT);
        if (clean.startsWith("[") && clean.endsWith("]")) {
            clean = clean.substring(1, clean.length() - 1);
        }
        return clean.endsWith(".") ? clean.substring(0, clean.length() - 1) : clean;
    }
}
