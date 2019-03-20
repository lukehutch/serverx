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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;

import serverx.utils.WebUtils.EscapeAmpersand;

/**
 * StringUtils.
 */
public class StringUtils {
    /** The Constant NBSP_CHAR. */
    public static final char NBSP_CHAR = (char) 0x00A0;

    /** The Constant IS_UNICODE_WHITESPACE. */
    private static final BitSet IS_UNICODE_WHITESPACE = new BitSet(1 << 16);

    static {
        // Valid unicode whitespace chars, see:
        // http://stackoverflow.com/questions/4731055/whitespace-matching-regex-java
        final String wsChars = ""//
                + (char) 0x0009 // CHARACTER TABULATION
                + (char) 0x000A // LINE FEED (LF)
                + (char) 0x000B // LINE TABULATION
                + (char) 0x000C // FORM FEED (FF)
                + (char) 0x000D // CARRIAGE RETURN (CR)
                + (char) 0x0020 // SPACE
                + (char) 0x0085 // NEXT LINE (NEL) 
                + NBSP_CHAR // NO-BREAK SPACE
                + (char) 0x1680 // OGHAM SPACE MARK
                + (char) 0x180E // MONGOLIAN VOWEL SEPARATOR
                + (char) 0x2000 // EN QUAD 
                + (char) 0x2001 // EM QUAD 
                + (char) 0x2002 // EN SPACE
                + (char) 0x2003 // EM SPACE
                + (char) 0x2004 // THREE-PER-EM SPACE
                + (char) 0x2005 // FOUR-PER-EM SPACE
                + (char) 0x2006 // SIX-PER-EM SPACE
                + (char) 0x2007 // FIGURE SPACE
                + (char) 0x2008 // PUNCTUATION SPACE
                + (char) 0x2009 // THIN SPACE
                + (char) 0x200A // HAIR SPACE
                + (char) 0x2028 // LINE SEPARATOR
                + (char) 0x2029 // PARAGRAPH SEPARATOR
                + (char) 0x202F // NARROW NO-BREAK SPACE
                + (char) 0x205F // MEDIUM MATHEMATICAL SPACE
                + (char) 0x3000; // IDEOGRAPHIC SPACE
        for (int i = 0; i < wsChars.length(); i++) {
            IS_UNICODE_WHITESPACE.set(wsChars.charAt(i));
        }
    }

    /**
     * Checks if is unicode whitespace.
     *
     * @param c
     *            the c
     * @return true, if is unicode whitespace
     */
    public static boolean isUnicodeWhitespace(final char c) {
        return IS_UNICODE_WHITESPACE.get(c);
    }

    /**
     * Contains non whitespace char.
     *
     * @param cs
     *            the cs
     * @return true, if successful
     */
    public static boolean containsNonWhitespaceChar(final CharSequence cs) {
        for (int i = 0; i < cs.length(); i++) {
            if (!isUnicodeWhitespace(cs.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Contains whitespace.
     *
     * @param cs
     *            the cs
     * @return true, if successful
     */
    public static boolean containsWhitespace(final CharSequence cs) {
        for (int i = 0; i < cs.length(); i++) {
            if (isUnicodeWhitespace(cs.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Contains uppercase char.
     *
     * @param s
     *            the s
     * @return true, if successful
     */
    public static boolean containsUppercaseChar(final CharSequence s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isUpperCase(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Unicode trim char sequence.
     *
     * @param cs
     *            the cs
     * @return the char sequence
     */
    public static CharSequence unicodeTrimCharSequence(final CharSequence cs) {
        int i;
        for (i = 0; i < cs.length(); i++) {
            if (!isUnicodeWhitespace(cs.charAt(i))) {
                break;
            }
        }
        int j;
        for (j = cs.length() - 1; j >= 0; --j) {
            if (!isUnicodeWhitespace(cs.charAt(j))) {
                break;
            }
        }
        return i <= j ? cs.subSequence(i, j + 1) : cs.subSequence(0, 0);
    }

    /**
     * Unicode trim.
     *
     * @param str
     *            the str
     * @return the string
     */
    public static final String unicodeTrim(final String str) {
        if (str == null || str.length() == 0
                || (!isUnicodeWhitespace(str.charAt(0)) && !isUnicodeWhitespace(str.charAt(str.length() - 1)))) {
            return str;
        }
        return unicodeTrimCharSequence(str).toString();
    }

    /**
     * Unicode trim.
     *
     * @param str
     *            the str
     * @return the char sequence
     */
    public static final CharSequence unicodeTrim(final CharSequence str) {
        if (str == null || str.length() == 0
                || (!isUnicodeWhitespace(str.charAt(0)) && !isUnicodeWhitespace(str.charAt(str.length() - 1)))) {
            return str;
        }
        return unicodeTrimCharSequence(str);
    }

    /**
     * Turn runs of one or more Unicode whitespace characters into a single space, with the exception of
     * non-breaking spaces, which are left alone (i.e. they are not absorbed into runs of whitespace).
     *
     * @param val
     *            the val
     * @return the string
     */
    public static final String normalizeSpacing(final String val) {
        boolean prevWasWS = false, needToNormalize = false;
        for (int i = 0; i < val.length(); i++) {
            final char c = val.charAt(i);
            final boolean isWS = c != NBSP_CHAR && isUnicodeWhitespace(c);
            if ((isWS && prevWasWS) || (isWS && c != ' ')) {
                // Found a run of spaces, or non-space whitespace chars
                needToNormalize = true;
                break;
            }
            prevWasWS = isWS;
        }
        prevWasWS = false;
        if (needToNormalize) {
            final StringBuilder buf = new StringBuilder();
            for (int i = 0; i < val.length(); i++) {
                final char c = val.charAt(i);
                final boolean isWS = c != NBSP_CHAR && isUnicodeWhitespace(c);
                if (isWS) {
                    if (!prevWasWS) {
                        // Replace a run of any whitespace characters with a single space
                        buf.append(' ');
                    }
                } else {
                    // Not whitespace
                    buf.append(c);
                }
                prevWasWS = isWS;
            }
            return buf.toString();
        } else {
            // Nothing to normalize
            return val;
        }
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * String splitter, this fixes the problem that String.split() has of losing the last token if it's empty. It
     * also uses CharSequences rather than allocating new String objects. Also faster than String.split() because it
     * doesn't support regular expressions.
     *
     * @param str
     *            the str
     * @param sep
     *            the sep
     * @return the array list
     */
    public static ArrayList<CharSequence> splitAsList(final String str, final String sep) {
        final int strLen = str.length();
        final int sepLen = sep.length();
        assert sepLen > 0;

        final ArrayList<CharSequence> parts = new ArrayList<CharSequence>();
        for (int curr = 0; curr <= strLen;) {
            // Look for next token
            int next = str.indexOf(sep, curr);
            // Read to end if none
            if (next < 0) {
                next = strLen;
            }
            // Add next token
            parts.add(str.subSequence(curr, next));
            // Move to end of separator, or past end of string if we're at the end
            // (by stopping when curr <= strLen rather than when curr < strLen,
            // we avoid the problem inherent in the Java standard libraries of
            // dropping the last field if it's empty; fortunately
            // str.indexOf(sep, curr) still works when curr == str.length()
            // without throwing an index out of range exception).
            curr = next + sepLen;
        }
        return parts;
    }

    /**
     * Split as array.
     *
     * @param str
     *            the str
     * @param sep
     *            the sep
     * @return the char sequence[]
     */
    public static CharSequence[] splitAsArray(final String str, final String sep) {
        final ArrayList<CharSequence> list = splitAsList(str, sep);
        return list.toArray(new CharSequence[0]);
    }

    /**
     * Split as list of string.
     *
     * @param str
     *            the str
     * @param sep
     *            the sep
     * @return the array list
     */
    public static ArrayList<String> splitAsListOfString(final String str, final String sep) {
        final ArrayList<CharSequence> list = splitAsList(str, sep);
        final ArrayList<String> listOfString = new ArrayList<String>(list.size());
        for (final CharSequence cs : list) {
            listOfString.add(cs.toString());
        }
        return listOfString;
    }

    /**
     * For compatibility only, slower because it creates new String objects for each CharSequence.
     *
     * @param str
     *            the str
     * @param sep
     *            the sep
     * @return the string[]
     */
    public static String[] split(final String str, final String sep) {
        final ArrayList<CharSequence> list = splitAsList(str, sep);
        final String[] arr = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i).toString();
        }
        return arr;
    }

    /**
     * Split and trim.
     *
     * @param str
     *            the str
     * @param sep
     *            the sep
     * @return the string[]
     */
    public static String[] splitAndTrim(final String str, final String sep) {
        final ArrayList<CharSequence> list = splitAsList(str, sep);
        final String[] arr = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = unicodeTrimCharSequence(list.get(i)).toString();
        }
        return arr;
    }

    /**
     * The Interface StringToStringMapper.
     */
    @FunctionalInterface
    public interface StringToStringMapper {

        /**
         * Map.
         *
         * @param str
         *            the str
         * @return the string
         */
        public String map(String str);
    }

    /**
     * Stringify elements of an Iterable, inserting a delimiter between adjacent elements after first applying a
     * given map function to each element.
     *
     * @param <T>
     *            the generic type
     * @param iterable
     *            the iterable
     * @param delim
     *            the delim
     * @param mapper
     *            the mapper
     * @return the string
     */
    public static <T> String join(final Iterable<T> iterable, final String delim,
            final StringToStringMapper mapper) {
        if (iterable == null) {
            return "";
        }
        final StringBuilder buf = new StringBuilder();
        int idx = 0;
        for (final T item : iterable) {
            if (idx++ > 0) {
                buf.append(delim);
            }
            buf.append(mapper.map(item.toString()));
        }
        return buf.toString();
    }

    /**
     * Stringify elements of an Iterable, inserting a delimiter between adjacent elements.
     *
     * @param <T>
     *            the generic type
     * @param iterable
     *            the iterable
     * @param delim
     *            the delim
     * @return the string
     */
    public static <T> String join(final Iterable<T> iterable, final String delim) {
        if (iterable == null) {
            return null;
        }
        final StringBuilder buf = new StringBuilder();
        int idx = 0;
        for (final T item : iterable) {
            if (idx++ > 0) {
                buf.append(delim);
            }
            buf.append(item.toString());
        }
        return buf.toString();
    }

    /**
     * Stringify elements of an array, inserting a delimiter between adjacent elements.
     *
     * @param <T>
     *            the generic type
     * @param array
     *            the array
     * @param delim
     *            the delim
     * @return the string
     */
    public static <T> String join(final T[] array, final String delim) {
        if (array == null) {
            return null;
        }
        final StringBuilder buf = new StringBuilder();
        for (int i = 0, n = array.length; i < n; i++) {
            if (i > 0) {
                buf.append(delim);
            }
            buf.append(array[i].toString());
        }
        return buf.toString();
    }

    /**
     * Stringify elements of an Iterable, inserting ", " as a delimiter between adjacent elements after first
     * sorting the elements into lexicographic order.
     *
     * @param <T>
     *            the generic type
     * @param iterable
     *            the iterable
     * @return the string
     */
    public static <T extends Comparable<T>> String joinCommaSeparatedSorted(final Iterable<T> iterable) {
        if (iterable == null) {
            return "";
        }
        final ArrayList<T> sorted = new ArrayList<>();
        for (final T val : iterable) {
            sorted.add(val);
        }
        Collections.sort(sorted);
        return join(sorted, ", ");
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Return leaf name of a path or URI (part after last '/').
     *
     * @param filePath
     *            the file path
     * @return the string
     */
    public static String leafName(final String filePath) {
        return filePath.substring(filePath.lastIndexOf('/') + 1);
    }

    // -----------------------------------------------------------------------------------------------------

    /** The spaces. */
    private static String[] SPACES = new String[256];

    static {
        final StringBuilder buf = new StringBuilder();
        for (int i = 1; i < SPACES.length; i++) {
            buf.append(' ');
        }
        final String allSpaces = buf.toString();
        for (int i = 0; i < SPACES.length; i++) {
            SPACES[i] = allSpaces.substring(0, i);
        }
    }

    /**
     * Spaces.
     *
     * @param n
     *            the n
     * @return the char sequence
     */
    public static CharSequence spaces(final int n) {
        if (n < SPACES.length) {
            return SPACES[n];
        }
        final StringBuilder buf = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            buf.append(' ');
        }
        return buf.toString();
    }

    /**
     * Append spaces.
     *
     * @param numSpaces
     *            the num spaces
     * @param buf
     *            the buf
     */
    public static void appendSpaces(final int numSpaces, final StringBuilder buf) {
        for (int numSpacesToAppend = numSpaces; numSpacesToAppend > 0;) {
            final int numSpacesToAppendThisIter = Math.min(SPACES.length - 1, numSpacesToAppend);
            buf.append(SPACES[numSpacesToAppendThisIter]);
            numSpacesToAppend -= numSpacesToAppendThisIter;
        }
    }

    /**
     * Perform prettyprinting indentation if last character was a newline.
     *
     * @param indentLevel
     *            the indent level
     * @param buf
     *            the buf
     */
    public static void indent(final int indentLevel, final StringBuilder buf) {
        // See if the line is already sufficiently indented
        int numTrailingSpaces = 0;
        boolean hasNewline = buf.length() == 0;
        for (int i = buf.length() - 1; i >= 0; --i) {
            final char c = buf.charAt(i);
            if (c == ' ') {
                numTrailingSpaces++;
            } else {
                hasNewline = c == '\n';
                break;
            }
        }
        if (!hasNewline) {
            buf.append('\n');
            numTrailingSpaces = 0;
        }
        if (numTrailingSpaces > indentLevel && numTrailingSpaces > 0) {
            // Over-indented for element that turned out to be empty -- outdent again
            buf.setLength(buf.length() - (numTrailingSpaces - indentLevel));
        } else {
            appendSpaces(indentLevel - numTrailingSpaces, buf);
        }
    }

    /**
     * The Interface StringEscaper.
     */
    @FunctionalInterface
    public static interface StringEscaper {

        /**
         * Escape.
         *
         * @param string
         *            the string
         * @param buf
         *            the buf
         */
        public void escape(CharSequence string, StringBuilder buf);
    }

    /**
     * Indent the lines of the given string, inserting new indents at each newline.
     *
     * @param str
     *            the str
     * @param escaper
     *            the escaper
     * @param indentLevel
     *            the indent level
     * @param retainNewline
     *            the retain newline
     * @param buf
     *            the buf
     */
    private static void indentLines(final String str, final StringEscaper escaper, final int indentLevel,
            final boolean retainNewline, final StringBuilder buf) {
        if (str == null) {
            return;
        }
        int start = 0, next;
        final int len = str.length();
        do {
            next = str.indexOf('\n', start);
            if (next < 0) {
                next = len;
            }
            escaper.escape(start == 0 && next == len ? str : str.subSequence(start, next), buf);
            start = next + 1;
            if (next < len) {
                if (retainNewline) {
                    buf.append('\n');
                }
                appendSpaces(indentLevel, buf);
            }
        } while (next < len);
    }

    /** The no escape. */
    private static StringEscaper NO_ESCAPE = (str, buf) -> buf.append(str);

    /** The html escape. */
    private static StringEscaper HTML_ESCAPE = (str, buf) -> WebUtils.encodeForHTML(str,
            /* escapeAmpersand = */ EscapeAmpersand.ALWAYS, /* preserveWhitespaceRuns = */ true,
            /* preserveNewline = */ true, /* turnNewlineIntoBreak = */ false, buf);

    /**
     * Append unescaped.
     *
     * @param str
     *            the str
     * @param indent
     *            the indent
     * @param indentLevel
     *            the indent level
     * @param buf
     *            the buf
     */
    public static void appendUnescaped(final String str, final boolean indent, final int indentLevel,
            final StringBuilder buf) {
        if (indent) {
            indentLines(str, NO_ESCAPE, indentLevel, /* retainNewline = */ true, buf);
        } else {
            buf.append(str);
        }
    }

    /**
     * Append escaped.
     *
     * @param str
     *            the str
     * @param forAttrVal
     *            the for attr val
     * @param indent
     *            the indent
     * @param indentLevel
     *            the indent level
     * @param buf
     *            the buf
     */
    public static void appendEscaped(final String str, final boolean forAttrVal, final boolean indent,
            final int indentLevel, final StringBuilder buf) {
        if (indent) {
            if (forAttrVal) {
                indentLines(str, HTML_ESCAPE, indentLevel, /* retainNewline = */ false, buf);
            } else {
                indentLines(str, HTML_ESCAPE, indentLevel, /* retainNewline = */ true, buf);
            }
        } else {
            buf.append(str);
        }
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Read all input from an InputStream and return it as a String.
     *
     * @param inputStream
     *            the input stream
     * @return the string
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    public static String readWholeFile(final InputStream inputStream) throws IOException {
        final BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
        final StringBuilder buf = new StringBuilder();
        for (String line; (line = reader.readLine()) != null;) {
            buf.append(line);
            buf.append('\n');
        }
        return buf.toString();
    }
}
