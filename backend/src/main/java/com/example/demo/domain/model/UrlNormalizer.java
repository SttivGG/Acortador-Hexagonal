package com.example.demo.domain.model;

import java.net.URI;
import java.util.Locale;

/** Only folds scheme/host case, default ports and the empty HTTP path. */
public final class UrlNormalizer {
    private UrlNormalizer() { }

    public static String normalize(String input) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("Debes ingresar un enlace");
        String url = input.trim();
        if (url.length() > 2048) throw new IllegalArgumentException("El enlace es demasiado largo");
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getPort() > 65535) {
                throw new IllegalArgumentException();
            }
            int port = uri.getPort();
            String authority = uri.getHost().toLowerCase(Locale.ROOT);
            if (port != -1 && !(scheme.equals("http") && port == 80)
                    && !(scheme.equals("https") && port == 443)) authority += ":" + port;
            String path = uri.getRawPath();
            return scheme + "://" + authority + (path == null || path.isEmpty() ? "/" : path)
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Ingresa una URL válida que comience por http:// o https://");
        }
    }
}
