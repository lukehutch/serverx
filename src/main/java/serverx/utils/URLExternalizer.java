package serverx.utils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * URLUtils.
 */
public class URLExternalizer {
    /**
     * Path plus parms of url.
     *
     * @param uri
     *            the uri
     * @return the string
     */
    private static String pathPlusParmsOfUrl(final URI uri) {
        final var path = blankOrMustStartWith(uri.getRawPath(), "/");
        final var query = blankOrMustStartWith(uri.getRawQuery(), "?");
        final var fragment = blankOrMustStartWith(uri.getRawFragment(), "#");
        return path + query + fragment;
    }

    /**
     * Parses the port str.
     *
     * @param portStr
     *            the port str
     * @return the integer
     */
    private static Integer parsePortStr(final String portStr) {
        Integer port = null;
        if (portStr != null && !portStr.isEmpty()) {
            try {
                port = Integer.valueOf(portStr);
            } catch (final NumberFormatException e) {
                // Ignore
            }
        }
        return port;
    }

    /**
     * Divide port.
     *
     * @param hostWithOptionalPort
     *            the host with optional port
     * @return the entry
     */
    private static Entry<String, Integer> dividePort(final String hostWithOptionalPort) {
        String host = null;
        Integer optionalPort = null;
        if (hostWithOptionalPort.startsWith("[")) {
            // IPv6
            final int idx0 = hostWithOptionalPort.indexOf(']');
            host = idx0 < 0 ? hostWithOptionalPort : hostWithOptionalPort.substring(0, idx0 + 1);
            final int idx1 = hostWithOptionalPort.indexOf("]:");
            optionalPort = idx1 < 0 ? null : parsePortStr(hostWithOptionalPort.substring(idx1 + 2));
        } else {
            // IPv6
            final int idx = hostWithOptionalPort.indexOf(':');
            host = idx < 0 ? hostWithOptionalPort : hostWithOptionalPort.substring(0, idx);
            optionalPort = idx < 0 ? null : parsePortStr(hostWithOptionalPort.substring(idx + 1));
        }
        return new SimpleEntry<>(host, optionalPort);
    }

    /**
     * Blank or must start with.
     *
     * @param str
     *            the str
     * @param prefix
     *            the prefix
     * @return the string
     */
    private static String blankOrMustStartWith(final String str, final String prefix) {
        return str == null || str.isBlank() ? "" : str.startsWith(prefix) ? str : prefix + str;
    }

    /**
     * Externalize URLs.
     *
     * @param requestUri
     *            the request uri
     * @param resolveUrl
     *            the resolve url
     * @param headers
     *            the headers
     * @return the uri
     * @throws URISyntaxException
     *             the URI syntax exception
     */
    private static URI externalizeURI(final URI requestUri, final String resolveUrl,
            final Map<String, String> headers) throws URISyntaxException {
        // From: https://stackoverflow.com/a/39564200/3950982

        // Special case -- don't touch fully qualified URLs
        if (resolveUrl.startsWith("http://") || resolveUrl.startsWith("https://")) {
            return new URI(resolveUrl);
        }

        var forwardedScheme = headers.get("X-Forwarded-Proto");
        if (forwardedScheme == null) {
            forwardedScheme = headers.get("X-Forwarded-Scheme");
        }
        if (forwardedScheme == null) {
            forwardedScheme = requestUri.getScheme();
        }

        // Special case for //host/something URLs
        if (resolveUrl.startsWith("//")) {
            return new URI(forwardedScheme + ":" + resolveUrl);
        }

        var forwardedHostHeader = headers.get("X-Forwarded-Host");
        if (forwardedHostHeader == null) {
            forwardedHostHeader = requestUri.getHost();
        }

        final var forwardedHostAndPort = dividePort(forwardedHostHeader);

        var requestPort = parsePortStr(headers.get("X-Forwarded-Port"));
        if (requestPort == null && forwardedHostAndPort.getValue() != null) {
            requestPort = forwardedHostAndPort.getValue();
        }
        if (requestPort == null) {
            final var requestUriPort = requestUri.getPort();
            requestPort = requestUriPort > 0 ? requestUriPort : "https".equals(forwardedScheme) ? 443 : 80;
        }

        final var finalPortStr = "https".equals(forwardedScheme) && requestPort == 443 ? ""
                : "http".equals(forwardedScheme) && requestPort == 80 ? "" : ":" + requestPort;

        final var restOfUrl = pathPlusParmsOfUrl(requestUri);
        return new URI(forwardedScheme + "://" + forwardedHostAndPort.getKey() + finalPortStr + restOfUrl)
                .resolve(resolveUrl);
    }

    /**
     * Return a related URL as public URL.
     *
     * @param ctx
     *            the ctx
     * @param resolveUrl
     *            the resolve url
     * @return the string
     */
    public static String externalizeURL(final RoutingContext ctx, final String resolveUrl) {
        final var cleanHeaders = ctx.request().headers().entries().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        try {
            return externalizeURI(new URI(ctx.request().absoluteURI()), resolveUrl, cleanHeaders).toString();
        } catch (final URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Return request URL as public URL.
     *
     * @param ctx
     *            the ctx
     * @return the string
     */
    public static String externalizeURL(final RoutingContext ctx) {
        try {
            return externalizeURL(ctx, pathPlusParmsOfUrl(new URI(ctx.request().absoluteURI())));
        } catch (final URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Return a {@link Handler} that rewrites HTTP requests into HTTPS.
     *
     * @param httpsRedirectPort
     *            the https redirect port
     * @return the handler
     */
    public static Handler<RoutingContext> httpToHttpsHandler(final int httpsRedirectPort) {
        return ctx -> {
            // From https://stackoverflow.com/a/39564571/3950982
            try {
                final URI externalUri = new URI(externalizeURL(ctx));
                if (externalUri.getScheme().equals("http")) {
                    final var httpsExternalUri = new URI("https", //
                            externalUri.getUserInfo(), //
                            externalUri.getHost(), //
                            /* port = */ httpsRedirectPort, //
                            externalUri.getRawPath(), //
                            externalUri.getRawQuery(), //
                            externalUri.getRawFragment());
                    ctx.response().putHeader("location", httpsExternalUri.toString())
                            .setStatusCode(/* redirect */ 302).end();
                } else {
                    ctx.next();
                }
            } catch (final Exception e) {
                ctx.fail(e);
            }
        };
    }
}
