package com.genesis.p2p.util.net;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/**
 * A robust, P2P-friendly URL/authority parser supporting:
 *
 * - IPv4          → 192.168.1.10:8080
 * - IPv6          → [2001:db8::1]:9000 or 2001:db8::1
 * - Hostnames     → node.example.com:7000
 * - Schemes       → tcp://host:5000, udp://host, ws://peer
 * - Defaults      → with default port or scheme if missing
 *
 * This parser does NOT throw unless the input is unrecoverable.
 * It attempts graceful fallback when possible.
 */
public final class UrlParser {

    /** Immutable parsed result */
    public static final class ParsedUrl {
        private final String scheme;
        private final String host;
        private final int port;

        public ParsedUrl(String scheme, String host, int port) {
            this.scheme = Objects.requireNonNull(scheme);
            this.host = Objects.requireNonNull(host);
            this.port = port;
        }

        public String scheme() { return scheme; }
        public String host() { return host; }
        public int port() { return port; }

        public InetSocketAddress toSocketAddress() {
            return new InetSocketAddress(host, port);
        }

        @Override
        public String toString() {
            return scheme + "://" + host + ":" + port;
        }
    }

    private UrlParser() {} // no instance

    // ───────────────────────────────────────────────────────────────
    // Public API
    // ───────────────────────────────────────────────────────────────

    /**
     * Parse a URL or authority string using default scheme/port.
     *
     * Examples:
     *   parse("192.168.1.10:9000", "tcp", 8080)
     *   parse("udp://peer.local", "udp", 5000)
     *   parse("[2001:db8::1]:7777", "tcp", 7000)
     */
    public static ParsedUrl parse(String raw,
                                  String defaultScheme,
                                  int defaultPort) {

        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("URL cannot be null/empty");
        }

        raw = raw.trim();

        // If string doesn't contain "://", treat it like authority (host:port)
        if (!raw.contains("://")) {
            return parseAuthorityOnly(raw, defaultScheme, defaultPort);
        }

        // Otherwise treat it as URI
        return parseUriStyle(raw, defaultScheme, defaultPort);
    }

    // ───────────────────────────────────────────────────────────────
    // Parser for authority-only forms (host:port, IPv6, etc.)
    // ───────────────────────────────────────────────────────────────

    private static ParsedUrl parseAuthorityOnly(String raw,
                                                String defaultScheme,
                                                int defaultPort) {

        String scheme = defaultScheme;
        String host;
        int port = defaultPort;

        // IPv6 without scheme: [2001:db8::1]:9000 or 2001:db8::1
        if (raw.startsWith("[")) {
            int end = raw.indexOf(']');
            if (end < 0) throw new IllegalArgumentException("Invalid IPv6 address: " + raw);

            host = raw.substring(1, end);

            if (raw.length() > end + 1 && raw.charAt(end + 1) == ':') {
                port = parsePort(raw.substring(end + 2), defaultPort);
            }

        } else if (raw.contains(":")) {
            // hostname:port or IPv4:port
            int idx = raw.lastIndexOf(':');

            host = raw.substring(0, idx);
            port = parsePort(raw.substring(idx + 1), defaultPort);

        } else {
            // no port provided
            host = raw;
        }

        if (host.isEmpty()) throw new IllegalArgumentException("Missing hostname in: " + raw);
        return new ParsedUrl(scheme, host, port);
    }

    // ───────────────────────────────────────────────────────────────
    // Parser for URI-style ("tcp://host:port")
    // ───────────────────────────────────────────────────────────────

    private static ParsedUrl parseUriStyle(String raw,
                                           String fallbackScheme,
                                           int fallbackPort) {

        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URI: " + raw, e);
        }

        String scheme = (uri.getScheme() != null) ? uri.getScheme() : fallbackScheme;

        String host = uri.getHost();
        if (host == null) {
            // URI parsers sometimes fail on IPv6 or no slashes ─ manually repair
            String authority = uri.getRawAuthority();
            if (authority != null) {
                return parseAuthorityOnly(authority, scheme, fallbackPort);
            }
            throw new IllegalArgumentException("URI missing host: " + raw);
        }

        int port = (uri.getPort() > 0) ? uri.getPort() : fallbackPort;

        return new ParsedUrl(scheme, host, port);
    }

    // ───────────────────────────────────────────────────────────────
    // Utility: Safe port parsing
    // ───────────────────────────────────────────────────────────────

    private static int parsePort(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return fallback;
        }
    }
}
