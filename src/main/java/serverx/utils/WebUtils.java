/**
 * This file is part of the Gribbit Web Framework.
 * 
 *     https://github.com/lukehutch/gribbit
 * 
 * @author Luke Hutchison
 * 
 * --
 * 
 * @license Apache 2.0 
 * 
 * Copyright 2015 Luke Hutchison
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package serverx.utils;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.logging.Level;
import java.util.regex.Pattern;

import io.vertx.ext.web.RoutingContext;
import serverx.server.ServerxVerticle;
import serverx.utils.UTF8.UTF8Exception;

/**
 * WebUtils.
 */
public class WebUtils {
    /**
     * Pattern for valid id or name attribute values. NB '.' and ':' are also technically allowed in the standard,
     * but they cause problems with jQuery, so they are disallowed here. Also note that both character cases are
     * allowed, but browsers may not handle case sensitivity correctly in all related contexts. See
     * http://stackoverflow.com/questions/70579/what-are-valid-values-for-the-id-attribute-in-html .
     */
    public static final Pattern VALID_HTML_NAME_OR_ID = Pattern.compile("[a-zA-Z][\\w\\-]*");

    /**
     * Pattern for CSS class name (includes ' ', because class attributes can list multiple classes).
     * 
     * NB CSS class names can start with '-' or '_', but this is technically reserved for vendor-specific
     * extensions, so we don't allow that here.
     */
    public static final Pattern VALID_CSS_ID = Pattern
            .compile("\\s*[a-zA-Z][_a-zA-Z0-9\\-]*(\\s+[a-zA-Z][_a-zA-Z0-9\\-]*)*\\s*");

    /**
     * Pattern for valid email address: from http://www.regular-expressions.info/email.html .
     * 
     * N.B. this is different than the validation performed by Chrome's type="email" form field validation (it
     * pretty much only requires an '@' character somewhere), so it's possible to submit form data like "x@y" that
     * passes Chrome's validation but fails serverside validation.
     */
    public static final Pattern VALID_EMAIL_ADDRESS = Pattern
            .compile("[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*"
                    + "@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?");

    // -----------------------------------------------------------------------------------------------------

    /** HTML tags that should not be closed. http://www.w3.org/TR/html-markup/syntax.html#void-element */
    public static final HashSet<String> VOID_ELEMENTS = toSet(
            new String[] { "area", "base", "br", "col", "command", "embed", "hr", "img", "input", "keygen", "link",
                    "meta", "param", "source", "track", "wbr", "!doctype", "!DOCTYPE" });

    /** HTML inline elements. https://developer.mozilla.org/en-US/docs/Web/HTML/Inline_elemente */
    public static final HashSet<String> INLINE_ELEMENTS = toSet(
            new String[] { "a", "abbr", "acronym", "b", "bdo", "big", "br", "button", "cite", "code", "dfn", "em",
                    "i", "img", "input", "kbd", "label", "map", "object", "q", "samp", "select", "small", "span",
                    "strong", "sub", "sup", "textarea", "title", "tt", "var" });

    // -----------------------------------------------------------------------------------------------------

    /**
     * HTML5 attributes that can take a URL: http://stackoverflow.com/questions/2725156/complete-list-of-html
     * -tag-attributes-which-have-a-url-value
     * 
     * (Applet and object tags are rejected during template loading, so those tags' attributes are not listed here,
     * but they also take URL params.)
     */
    private static final HashMap<String, HashSet<String>> URL_ELT_ATTRS = toMap(
            new String[] { "a.href", "area.href", "base.href", "blockquote.cite", "body.background", "del.cite",
                    "form.action", "frame.longdesc", "frame.src", "head.profile", "iframe.longdesc", "iframe.src",
                    "img.longdesc", "img.src", "img.usemap", "input.src", "input.usemap", "ins.cite", "link.href",
                    "q.cite", "script.src", "audio.src", "button.formaction", "command.icon", "embed.src",
                    "html.manifest", "input.formaction", "source.src", "video.poster", "video.src" },
            ".");

    /**
     * Return true if the given HTML attribute takes a URL as a value.
     *
     * @param tagName
     *            the tag name
     * @param attrName
     *            the attr name
     * @return true, if is URL attr
     */
    public static boolean isURLAttr(final String tagName, final String attrName) {
        final HashSet<String> attrs = URL_ELT_ATTRS.get(tagName.toLowerCase());
        return attrs == null ? false : attrs.contains(attrName.toLowerCase());
    }

    /**
     * Add a URL-typed attribute (used for adding attributes from custom elements or Polymer elements that take a
     * URL parameter, and therefore should be checked for URI validity).
     *
     * @param customElementName
     *            the custom element name
     * @param attrName
     *            the attr name
     */
    public static void addCustomURLAttr(final String customElementName, final String attrName) {
        HashSet<String> set = URL_ELT_ATTRS.get(customElementName);
        if (set == null) {
            URL_ELT_ATTRS.put(customElementName, set = new HashSet<>());
        }
        set.add(attrName);
    }

    /**
     * Whitelisted attributes that can't be exploited in an XSS attack -- source: https://www.owasp.org/index.
     * php/XSS_(Cross_Site_Scripting)_Prevention_Cheat_Sheet#XSS_Prevention_Rules_Summary
     */
    public static final HashSet<String> XSS_SAFE_ATTRS = toSet(new String[] { "align", "alink", "alt", "bgcolor",
            "border", "cellpadding", "cellspacing", "class", "color", "cols", "colspan", "coords", "dir", "face",
            "height", "hspace", "ismap", "lang", "marginheight", "marginwidth", "multiple", "nohref", "noresize",
            "noshade", "nowrap", "ref", "rel", "rev", "rows", "rowspan", "scrolling", "shape", "span", "summary",
            "tabindex", "title", "usemap", "valign", "value", "vlink", "vspace", "width" });

    // -----------------------------------------------------------------------------------------------------

    /**
     * Checks if is compressible content type.
     *
     * @param contentType
     *            the content type
     * @return true, if is compressible content type
     */
    public static boolean isCompressibleContentType(final String contentType) {
        return contentType != null && (contentType.startsWith("text/")
                || contentType.startsWith("application/javascript") || contentType.startsWith("application/json")
                || contentType.startsWith("application/xml") || contentType.startsWith("image/svg+xml")
                || contentType.startsWith("application/x-font-ttf"));
    }

    // -----------------------------------------------------------------------------------------------------

    /** Pattern for recognizing external URIs. */
    private static final Pattern EXTERNAL_URI = Pattern.compile("^((data:)|(http[s]?:))?\\/\\/.*");

    /**
     * Returns true if the URL is not null, empty or "#", but starts with "data:" or "http[s]:", and does not
     * contain an unexpanded template parameter "${...}" (which could lead to vulnerabilities given a
     * carefully-crafted URL parameter).
     *
     * @param hrefURI
     *            the href URI
     * @return true, if is local URL
     */
    public static boolean isLocalURL(final String hrefURI, final URI serverUri) {
        try {
            return
            // Not null / empty / "#..." / "/..."
            hrefURI != null && !hrefURI.isEmpty() && !hrefURI.startsWith("#") && !hrefURI.startsWith("/") //
            // Not template param
                    && !hrefURI.contains("{{")
                    // Not external/absolute URI, or is external URI but has same origin as server
                    && (!EXTERNAL_URI.matcher(hrefURI).matches() || hasSameOrigin(new URI(hrefURI), serverUri));
        } catch (final URISyntaxException e) {
            return false;
        }
    }

    private static boolean hasSameOrigin(final URI uri0, final URI uri1) {
        return Objects.equals(uri0.getScheme(), uri1.getScheme()) //
                && Objects.equals(uri0.getHost(), uri1.getHost()) //
                && Objects.equals(uri0.getPort(), uri1.getPort());
    }

    /**
     * Resolve a relative path in an URI attribute to an absolute path.
     *
     * @param hrefURL
     *            the URI to resolve, e.g. "../css/main.css"
     * @param baseURL
     *            the base URI to resolve relative to, without a trailing slash, e.g. "/site/res". If baseURL is "",
     *            then ".." and "." are still resolved, but URLs are not made absolute, i.e. "css/main.css" remains
     *            unchanged, but "../css/main.css" turns into "/css/main.css".
     * @param serverUri
     *            the server URI
     * @return the absolute path of the URI, e.g. "/site/css/main.css"
     */
    public static String resolveHREF(final String hrefURL, final String baseURL, final URI serverUri) {
        // Return original URL if it is empty, starts with "#", or is a template param
        if (isLocalURL(hrefURL, serverUri)) {
            // Build new path for the linked resource
            final StringBuilder hrefURLResolved = new StringBuilder(
                    hrefURL.startsWith("//") ? "//" : hrefURL.startsWith("/") ? "/" : baseURL);
            for (final CharSequence part : StringUtils.splitAsList(hrefURL, "/")) {
                if (part.length() == 0 || part.equals(".")) {
                    // Ignore
                } else if (part.equals("..")) {
                    // Move up one level (ignoring if we get back to root)
                    final int lastIdx = hrefURLResolved.lastIndexOf("/");
                    hrefURLResolved.setLength(lastIdx < 0 ? 0 : lastIdx);
                } else {
                    if (hrefURLResolved.length() > 0
                            && hrefURLResolved.charAt(hrefURLResolved.length() - 1) != '/') {
                        hrefURLResolved.append('/');
                    }
                    hrefURLResolved.append(part);
                }
            }
            return hrefURLResolved.toString();
        }
        return hrefURL;
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Unescape a URL segment, and turn it from UTF-8 bytes into a Java string.
     *
     * @param str
     *            the str
     * @return the string
     */
    public static String unescapeURLSegment(final String str) {
        boolean hasEscapedChar = false;
        for (int i = 0; i < str.length(); i++) {
            final char c = str.charAt(i);
            if ((c < 'a' || c > 'z') && (c < 'A' || c > 'Z') && (c < '0' || c > '9') && c != '-' && c != '.'
                    && c != '_') {
                hasEscapedChar = true;
                break;
            }
        }
        if (!hasEscapedChar) {
            return str;
        } else {
            byte[] buf = new byte[str.length()];
            int bufIdx = 0;
            for (int segIdx = 0, nSeg = str.length(); segIdx < nSeg; segIdx++) {
                final char c = str.charAt(segIdx);
                if (c == '%') {
                    // Decode %-escaped char sequence, e.g. %5D
                    if (segIdx > nSeg - 3) {
                        // Ignore truncated %-seq at end of string
                    } else {
                        final char c1 = str.charAt(++segIdx);
                        final int digit1 = c1 >= '0' && c1 <= '9' ? (c1 - '0')
                                : c1 >= 'a' && c1 <= 'f' ? (c1 - 'a' + 10)
                                        : c1 >= 'A' && c1 <= 'F' ? (c1 - 'A' + 10) : -1;
                        final char c2 = str.charAt(++segIdx);
                        final int digit2 = c2 >= '0' && c2 <= '9' ? (c2 - '0')
                                : c2 >= 'a' && c2 <= 'f' ? (c2 - 'a' + 10)
                                        : c2 >= 'A' && c2 <= 'F' ? (c2 - 'A' + 10) : -1;
                        if (digit1 < 0 || digit2 < 0) {
                            // Ignore invalid %-sequence
                        } else {
                            buf[bufIdx++] = (byte) ((digit1 << 4) | digit2);
                        }
                    }
                } else if (c <= 0x7f) {
                    buf[bufIdx++] = (byte) c;
                } else {
                    // Ignore invalid chars
                }
            }
            if (bufIdx < buf.length) {
                buf = Arrays.copyOf(buf, bufIdx);
            }
            try {
                return UTF8.utf8ToString(buf);
            } catch (final UTF8Exception e) {
                throw new IllegalArgumentException("Invalid UTF8 when trying to unescape " + str);
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Encode unsafe characters using %-encoding.
     *
     * @param buf
     *            the buf
     * @param c
     *            the c
     */
    private static void percentEncode(final StringBuilder buf, final char c) {
        buf.append('%');
        final int b1 = ((c & 0xf0) >> 4), b2 = c & 0x0f;
        buf.append((char) (b1 <= 9 ? '0' + b1 : 'a' + b1 - 10));
        buf.append((char) (b2 <= 9 ? '0' + b2 : 'a' + b2 - 10));
    }

    // Valid URL characters: see
    // http://goo.gl/JNmVMa
    // http://goo.gl/OZ9OOZ
    // http://goo.gl/QFk9R7

    /**
     * Convert a single URL segment (between slashes) to UTF-8, then encode any unsafe bytes.
     *
     * @param str
     *            the str
     * @return the string
     */
    public static String escapeURLSegment(final String str) {
        if (str == null) {
            return str;
        }
        boolean hasEscapedChar = false;
        for (int i = 0; i < str.length(); i++) {
            final char c = str.charAt(i);
            if ((c < 'a' || c > 'z') && (c < 'A' || c > 'Z') && (c < '0' || c > '9') && c != '-' && c != '.'
                    && c != '_') {
                hasEscapedChar = true;
                break;
            }
        }
        if (hasEscapedChar) {
            final StringBuilder buf = new StringBuilder(str.length() * 4);
            escapeURLSegment(str, buf);
            return buf.toString();
        } else {
            return str;
        }
    }

    /**
     * Convert a single URL segment (between slashes) to UTF-8, then encode any unsafe bytes.
     *
     * @param str
     *            the str
     * @param buf
     *            the buf
     */
    public static void escapeURLSegment(final String str, final StringBuilder buf) {
        if (str == null) {
            return;
        }
        final byte[] utf8Bytes = UTF8.stringToUTF8(str);
        for (int i = 0; i < utf8Bytes.length; i++) {
            final char c = (char) utf8Bytes[i];
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') //
                    || c == '-' || c == '.' || c == '_') {
                buf.append(c);
            } else {
                percentEncode(buf, c);
            }
        }
    }

    /**
     * Convert a URI query param key of the form "q" in "?q=v", %-encoding of UTF8 bytes for unusual characters.
     *
     * @param str
     *            the str
     * @param buf
     *            the buf
     */
    public static void escapeQueryParamKey(final String str, final StringBuilder buf) {
        escapeURLSegment(str, buf);
    }

    /**
     * Convert a URI query param value of the form "v" in "?q=v". We use '+' to escape spaces, by convention, and
     * %-encoding of UTF8 bytes for unusual characters.
     *
     * @param str
     *            the str
     * @param buf
     *            the buf
     */
    public static void escapeQueryParamVal(final String str, final StringBuilder buf) {
        if (str == null) {
            return;
        }
        escapeURLSegment(str.indexOf(' ') >= 0 ? str.replace(' ', '+') : str, buf);
    }

    /**
     * Escape query param key val.
     *
     * @param key
     *            the key
     * @param val
     *            the val
     * @param buf
     *            the buf
     */
    public static void escapeQueryParamKeyVal(final String key, final String val, final StringBuilder buf) {
        if (key == null || key.isEmpty()) {
            return;
        }
        escapeQueryParamKey(key, buf);
        buf.append('=');
        escapeQueryParamVal(val, buf);
    }

    /**
     * Build a string of escaped URL query param key-value pairs. Keys are in even indices, values are in the
     * following odd indices. The keys and values are URL-escaped and concatenated with '&' as a delimiter.
     *
     * @param keyValuePairs
     *            the key value pairs
     * @return the string
     */
    public static String buildQueryString(final String... keyValuePairs) {
        if (keyValuePairs == null || keyValuePairs.length == 0) {
            return "";
        }
        final StringBuilder buf = new StringBuilder();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            if (buf.length() > 0) {
                buf.append("&");
            }
            WebUtils.escapeQueryParamKeyVal(keyValuePairs[i],
                    i < keyValuePairs.length - 1 ? keyValuePairs[i + 1] : "", buf);
        }
        return buf.toString();
    }

    /**
     * Split a URI into pieces and encode each piece appropriately to make it safe for use as an HTML attribute
     * value. See:
     * 
     * https://www.owasp.org/index.php/XSS_(Cross_Site_Scripting)_Prevention_Cheat_Sheet#RULE_
     * .235_-_URL_Escape_Before_Inserting_Untrusted_Data_into_HTML_URL_Parameter_Values
     *
     * @param unsafeURI
     *            the unsafe URI
     * @return the string
     */
    public static String encodeURI(final String unsafeURI) {
        // Look for scheme
        int startIdx = 0;
        final int colonIdx = unsafeURI.indexOf(':');
        if (colonIdx > 0) {
            final int firstSlashIdx = unsafeURI.indexOf('/');
            if (firstSlashIdx > 0 && firstSlashIdx > colonIdx) {
                startIdx = colonIdx + 1;
            }
        }
        // Look for query params
        String[] parts;
        int endIdx = unsafeURI.length();
        final int paramIdx = unsafeURI.indexOf('?');
        if (paramIdx >= 0) {
            endIdx = paramIdx;
        }
        // Split part between scheme and query params at "/"
        if (startIdx == 0 && endIdx == unsafeURI.length()) {
            parts = StringUtils.split(unsafeURI, "/");
        } else {
            parts = StringUtils.split(unsafeURI.substring(startIdx, endIdx), "/");
        }
        final StringBuilder buf = new StringBuilder(unsafeURI.length() * 2);
        // Add scheme, if present
        buf.append(unsafeURI.substring(0, startIdx));
        // Escape each segment of URI separately until query params 
        for (int i = 0; i < parts.length; i++) {
            final String part = parts[i];
            if (i > 0) {
                buf.append('/');
            }
            // TODO: This will %-encode any unusual characters in the domain name, which will later
            // be rejected by java.net.URI. Need to use Punycode to represent general Unicode domains. 
            escapeURLSegment(part, buf);
        }
        // Add query params, if present
        if (paramIdx >= 0) {
            buf.append('?');
            final String[] qparts = StringUtils.split(unsafeURI.substring(paramIdx + 1), "&");
            for (int i = 0; i < qparts.length; i++) {
                if (i > 0) {
                    buf.append('&');
                }
                final int eqIdx = qparts[i].indexOf('=');
                final String key = eqIdx < 0 ? qparts[i] : qparts[i].substring(0, eqIdx);
                final String val = eqIdx < 0 ? "" : qparts[i].substring(eqIdx + 1);
                escapeQueryParamKeyVal(key, val, buf);
            }
        }
        return buf.toString();
    }

    /**
     * Run a URI through the Java URI parser class to validate the URI.
     *
     * @param uriStr
     *            the uri str
     * @return the parsed URI, or null if the URI is invalid.
     */
    public static final URI parseURI(final String uriStr) {
        // Double-check for XSS-unsafe characters in URIs. Most of these (except for single quote) are
        // caught by the URI parser, but just to be safe we also manually check here.
        for (int i = 0; i < uriStr.length(); i++) {
            final char c = uriStr.charAt(i);
            if (c < (char) 33 || c > (char) 126 || c == '<' || c == '>' || c == '\'' || c == '"' || c == '\\') {
                return null;
            }
        }
        try {
            // Returns new URI object if URI parses OK
            return new URI(uriStr);
        } catch (final URISyntaxException e) {
            return null;
        }
    }

    /**
     * Checks if is valid URL.
     *
     * @param urlStr
     *            the url str
     * @return true, if is valid URL
     */
    public static final boolean isValidURL(final String urlStr) {
        return parseURI(urlStr) != null;
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Encode (percent-escape) any illegal characters (or UTF-8 bytes of non-ASCII characters) in a PLAIN-type
     * cookie value.
     * 
     * See: http://stackoverflow.com/questions/1969232/allowed-characters-in-cookies
     * 
     * i.e. encode if (c <= 32 || c == '"' || c == ',' || c == ';' || c == '\' || c == '%'),
     * 
     * where the additional last test (c == '%') is needed to allow for '%' to itself be escaped.
     * 
     * N.B. the list of unsafe characters is larger if cookie values are not properly double-quoted.
     *
     * @param str
     *            the str
     * @return the string
     */
    public static String escapeCookieValue(final String str) {
        final byte[] utf8Bytes = UTF8.stringToUTF8(str);
        final StringBuilder buf = new StringBuilder(utf8Bytes.length * 3);
        for (int i = 0; i < utf8Bytes.length; i++) {
            final char c = (char) utf8Bytes[i];
            if (c <= 32 || c == '"' || c == ',' || c == ';' || c == '\\' || c == '%') {
                percentEncode(buf, c);
            } else {
                buf.append(c);
            }
        }
        return buf.toString();
    }

    /**
     * Decode (percent-unescape) a PLAIN-type cookie value that was encoded using escapeCookieValue().
     *
     * @param str
     *            the str
     * @return the string
     */
    public static String unescapeCookieValue(final String str) {
        return unescapeURLSegment(str);
    }

    // -----------------------------------------------------------------------------------------------------

    /** The Constant VALID_ENTITY. */
    public static final Pattern VALID_ENTITY = Pattern
            .compile("^&(#\\d\\d?\\d?\\d?\\d?|#x[\\da-fA-F][\\da-fA-F]?[\\da-fA-F]?[\\da-fA-F]?|[a-zA-Z]\\w+);.*");

    /**
     * The Enum EscapeAmpersand.
     */
    public static enum EscapeAmpersand {

        /** The never. */
        NEVER,
        /** The if not valid entity. */
        IF_NOT_VALID_ENTITY,
        /** The always. */
        ALWAYS;
    }

    /**
     * Encodes HTML-unsafe characters as HTML entities.
     * 
     * See OWASP XSS Rule #1 at https://www.owasp.org/index.php/XSS_(Cross_Site_Scripting)_Prevention_Cheat_Sheet
     *
     * @param unsafeStr
     *            The string to escape to make HTML-safe.
     * @param escapeAmpersand
     *            If ALWAYS, turn '&' into "&amp;". If IF_NOT_VALID_ENTITY, leave ampersands in place (i.e. assume
     *            they are already valid entity references of the form "&lt;", but if they don't validate as valid
     *            entity references, e.g. "H&M", then escape them (=> "H&amp;M"). If NEVER, leave '&' as it is --
     *            this could be used to force ampersands to be left alone, e.g. in URL attribute values.
     * 
     *            However, there is some ambiguity in the case of URL attributes containing '&', see:
     * 
     *            http://stackoverflow.com/questions/3705591/do-i-encode-ampersands-in-a-href
     * 
     *            This indicates that attribute values should probably always use ALWAYS, even in the case of URL
     *            attributes. Use NEVER at your peril.
     * @param preserveWhitespaceRuns
     *            If true, don't collapse multiple successive whitespaces into a single space.
     * @param preserveNewline
     *            If true, leave newline characters in the text, rather than turning them into a space.
     * @param turnNewlineIntoBreak
     *            If true, turn '\n' into a break element in the output.
     * @param buf
     *            the buf
     */
    public static void encodeForHTML(final CharSequence unsafeStr, final EscapeAmpersand escapeAmpersand,
            final boolean preserveWhitespaceRuns, final boolean preserveNewline, final boolean turnNewlineIntoBreak,
            final StringBuilder buf) {
        for (int i = 0, n = unsafeStr.length(); i < n; i++) {
            final char c = unsafeStr.charAt(i);
            switch (c) {
            case '&':
                switch (escapeAmpersand) {
                case ALWAYS:
                    buf.append('&');
                    break;
                case NEVER:
                    buf.append("&amp;");
                    break;
                case IF_NOT_VALID_ENTITY:
                    // If not escaping ampersands, still do smart escaping: if we come across an ampersand,
                    // it must start a valid entity. If not, escape the ampersand as &amp; .
                    final int end = Math.min(i + 32, n); // Assume entities can't be more than 32 chars long
                    final int start = i + 1;
                    final boolean validEntity = end > start && //
                            VALID_ENTITY.matcher(unsafeStr.subSequence(start, end)).matches();
                    if (validEntity) {
                        buf.append('&');
                    } else {
                        buf.append("&amp;");
                    }
                    break;
                }
                break;
            case '<':
                buf.append("&lt;");
                break;
            case '>':
                buf.append("&gt;");
                break;
            case '"':
                // We always escape double quotes, that way there's no chance that an HTML attribute renderer
                // accidentally forgets to set an option to true to escape quotes. (This would allow content
                // injection by breaking out of the attribute value)
                buf.append("&quot;");
                break;
            case '\\':
                buf.append("&lsol;");
                break;
            case '\'':
                // Always escape single quotes, in case some consumer of this HTML chooses to render attribute values
                // in single quotes and forgets to escape the content.
                buf.append("&#x27;"); // See http://goo.gl/FzoP6m
                break;

            // We don't escape '/', since this is not a dangerous char if attr values are always quoted
            //            case '/':
            //                buf.append("&#x2F;");
            //                break;

            // Encode a few common characters that like to get screwed up in some charset/browser variants
            case '—':
                buf.append("&mdash;");
                break;
            case '–':
                buf.append("&ndash;");
                break;
            case '“':
                buf.append("&ldquo;");
                break;
            case '”':
                buf.append("&rdquo;");
                break;
            case '‘':
                buf.append("&lsquo;");
                break;
            case '’':
                buf.append("&rsquo;");
                break;
            case '«':
                buf.append("&laquo;");
                break;
            case '»':
                buf.append("&raquo;");
                break;
            case '£':
                buf.append("&pound;");
                break;
            case '©':
                buf.append("&copy;");
                break;
            case '®':
                buf.append("&reg;");
                break;
            case StringUtils.NBSP_CHAR:
                buf.append("&nbsp;");
                break;
            case '\n':
                if (turnNewlineIntoBreak) {
                    buf.append("<br>");
                    break;
                } else if (preserveNewline) {
                    buf.append('\n');
                    break;
                }
                // else fall through to default, and handle newline as another control character
            default:
                // Non-escaped characters: turn control characters and Unicode whitespaces into regular spaces
                if (c <= 32 || StringUtils.isUnicodeWhitespace(c)) {
                    if (preserveWhitespaceRuns) {
                        buf.append(' ');
                    } else {
                        if (buf.length() > 0 && buf.charAt(buf.length() - 1) != ' ') {
                            // Don't insert another space if there's already one in the buffer
                            buf.append(' ');
                        }
                    }
                } else {
                    // Some other regular non-escaped / not-whitespace character, just add the character to the buffer
                    buf.append(c);
                    break;
                }
            }
        }
    }

    /**
     * Encodes HTML-unsafe characters as HTML entities.
     * 
     * See OWASP XSS Rule #1 at https://www.owasp.org/index.php/XSS_(Cross_Site_Scripting)_Prevention_Cheat_Sheet
     *
     * @param unsafeStr
     *            the unsafe str
     * @return the string
     */
    public static String encodeForHTML(final CharSequence unsafeStr) {
        final StringBuilder buf = new StringBuilder(unsafeStr.length() * 2);
        encodeForHTML(unsafeStr, /* escapeAmpersand = */EscapeAmpersand.ALWAYS, //
                /* preserveWhitespaceRuns = */false, //
                /* preserveNewline = */false, /* turnNewlineIntoBreak = */false, buf);
        return buf.toString();
    }

    /**
     * Encodes HTML-attr-unsafe chars (all non-alphanumeric chars less than 0xff) as hex codes or entities.
     *
     * @param unsafeStr
     *            the unsafe str
     * @param buf
     *            the buf
     */
    public static void encodeForHTMLAttribute(final CharSequence unsafeStr, final StringBuilder buf) {
        /**
         * From OWASP XSS prevention Rule #2: "Except for alphanumeric characters, escape all characters with ASCII
         * values less than 256 with the &#xHH; format (or a named entity if available) to prevent switching out of
         * the attribute. The reason this rule is so broad is that developers frequently leave attributes unquoted.
         * Properly quoted attributes can only be escaped with the corresponding quote. Unquoted attributes can be
         * broken out of with many characters, including [space] % * + , - / ; < = > ^ and |."
         * 
         * However, if we escape as aggressively as this, then we get URLs like
         * href="&#x2F;action&#x2F;log&#x2D;out". In Gribbit, attributes are all being quoted (with double quotes),
         * and URL attrs are handled specially, so just perform regular HTML escaping inside HTML attribute values,
         * although we'll escape the apostrophe just to be safe.
         */
        encodeForHTML(unsafeStr, /* escapeAmpersand = */EscapeAmpersand.ALWAYS, //
                /* preserveWhitespaceRuns = */false, //
                /* preserveNewline = */false, /* turnNewlineIntoBreak = */false, buf);
    }

    /**
     * Encodes HTML-attr-unsafe chars (all non-alphanumeric chars less than 0xff) as hex codes or entities.
     *
     * @param unsafeStr
     *            the unsafe str
     * @return the string
     */
    public static String encodeForHTMLAttribute(final CharSequence unsafeStr) {
        final StringBuilder buf = new StringBuilder(unsafeStr.length() * 2);
        encodeForHTMLAttribute(unsafeStr, buf);
        return buf.toString();
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Escape a string to be surrounded in double quotes in JSON.
     *
     * @param unsafeStr
     *            the unsafe str
     * @return the string
     */
    public static String escapeJSONString(final String unsafeStr) {
        final StringBuilder buf = new StringBuilder(unsafeStr.length() * 2);
        escapeJSONString(unsafeStr, buf);
        return buf.toString();
    }

    /**
     * Escape a string to be surrounded in double quotes in JSON.
     *
     * @param unsafeStr
     *            the unsafe str
     * @param buf
     *            the buf
     */
    public static void escapeJSONString(final String unsafeStr, final StringBuilder buf) {
        for (int i = 0, n = unsafeStr.length(); i < n; i++) {
            final char c = unsafeStr.charAt(i);
            // See http://www.json.org/ under "string"
            switch (c) {
            case '\\':
            case '"':
                // Forward slash can be escaped, but doesn't have to be.
                // Jackson doesn't escape it, and it makes URLs ugly.
                // case '/':
                buf.append('\\');
                buf.append(c);
                break;
            case '\b':
                buf.append("\\b");
                break;
            case '\t':
                buf.append("\\t");
                break;
            case '\n':
                buf.append("\\n");
                break;
            case '\f':
                buf.append("\\f");
                break;
            case '\r':
                buf.append("\\r");
                break;
            default:
                if (c < ' ') {
                    buf.append("\\u00");
                    final int d1 = (c) >> 4;
                    buf.append(d1 <= 9 ? (char) ('0' + d1) : (char) ('A' + d1 - 10));
                    final int d2 = (c) & 0xf;
                    buf.append(d2 <= 9 ? (char) ('0' + d2) : (char) ('A' + d2 - 10));
                } else {
                    buf.append(c);
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Returns true iff str is a valid email address.
     *
     * @param str
     *            the str
     * @return true, if is valid email addr
     */
    public static final boolean isValidEmailAddr(final String str) {
        return str != null && VALID_EMAIL_ADDRESS.matcher(str).matches();
    }

    /**
     * Trim whitespace from an email address, make it lowercase, and make sure it is valid. If it is not valid,
     * return null.
     *
     * @param email
     *            the email
     * @return the string
     */
    public static String validateAndNormalizeEmailAddr(final String email) {
        if (email == null) {
            return null;
        }
        final String emailNormalized = StringUtils.unicodeTrim(email).toLowerCase();
        if (isValidEmailAddr(emailNormalized)) {
            return emailNormalized;
        }
        return null;
    }

    // -----------------------------------------------------------------------------------------------------

    //    /**
    //     * Return true if the scheme, host and port match between the two URIs. Also returns null if one or both URIs
    //     * are null.
    //     */
    //    public static boolean sameOrigin(URI uri1, URI uri2) {
    //        if (uri1 == null || uri2 == null) {
    //            return false;
    //        }
    //        String scheme1 = uri1.getScheme();
    //        if (scheme1 == null) {
    //            scheme1 = GribbitProperties.useTLS ? "https" : "http";
    //        }
    //        String scheme2 = uri2.getScheme();
    //        if (scheme2 == null) {
    //            scheme2 = GribbitProperties.useTLS ? "https" : "http";
    //        }
    //        if (!scheme1.equals(scheme2)) {
    //            return false;
    //        }
    //        String host1 = uri1.getHost();
    //        if (host1 == null) {
    //            host1 = GribbitServer.host;
    //        }
    //        String host2 = uri2.getHost();
    //        if (host2 == null) {
    //            host2 = GribbitServer.host;
    //        }
    //        if (!host1.equals(host2)) {
    //            return false;
    //        }
    //        int port1 = uri1.getPort();
    //        if (port1 < 0) {
    //            if ("http".equals(scheme1) || "ws".equals(scheme1)) {
    //                port1 = 80;
    //            } else if ("https".equals(scheme1) || "wss".equals(scheme1)) {
    //                port1 = 443;
    //            }
    //        }
    //        int port2 = uri2.getPort();
    //        if (port2 < 0) {
    //            if ("http".equals(scheme2) || "ws".equals(scheme2)) {
    //                port2 = 80;
    //            } else if ("https".equals(scheme2) || "wss".equals(scheme2)) {
    //                port2 = 443;
    //            }
    //        }
    //        if (port1 != port2) {
    //            return false;
    //        }
    //        return true;
    //    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Check to see if a port is available.
     *
     * @param port
     *            the port to check for availability.
     * @return true, if successful
     */
    public static boolean portIsAvailable(final int port) {
        try (var ss = new ServerSocket(port); var ds = new DatagramSocket(port)) {
            return true;
        } catch (final IOException e) {
            return false;
        }
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Sets the failure status and log internal error.
     *
     * @param ctx
     *            the ctx
     * @return the int
     */
    public static int setFailureStatusAndLogInternalError(final RoutingContext ctx) {
        // Determine status code for failure
        var statusCode = ctx.statusCode();
        if (statusCode == 200 || statusCode <= 0) {
            statusCode = 500;
        }
        // Set response header
        ctx.response().setStatusCode(statusCode);
        // Log internal errors with stack trace
        if (statusCode == 500) {
            ServerxVerticle.logger.log(Level.SEVERE, "Internal error", ctx.failure());
        }
        return statusCode;
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * To set.
     *
     * @param <T>
     *            the generic type
     * @param elts
     *            the elts
     * @return the hash set
     */
    private static <T> HashSet<T> toSet(final T[] elts) {
        final HashSet<T> set = new HashSet<>();
        for (final T elt : elts) {
            set.add(elt);
        }
        return set;
    }

    /**
     * To map.
     *
     * @param kvPairs
     *            the kv pairs
     * @param separator
     *            the separator
     * @return the hash map
     */
    private static HashMap<String, HashSet<String>> toMap(final String[] kvPairs, final String separator) {
        final HashMap<String, HashSet<String>> map = new HashMap<>();
        for (final String kvPair : kvPairs) {
            final String[] parts = StringUtils.split(kvPair, separator);
            final String eltName = parts[0], attrName = parts[1];
            HashSet<String> set = map.get(eltName);
            if (set == null) {
                map.put(eltName, set = new HashSet<>());
            }
            set.add(attrName);
        }
        return map;
    }
}
