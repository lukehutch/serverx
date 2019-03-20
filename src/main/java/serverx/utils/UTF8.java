/**
 * This file is part of the Gribbit Web Framework.
 * 
 *     https://github.com/lukehutch/gribbit
 *     
 * Originally from:
 * 
 *     https://github.com/webbit/webbit/blob/master/src/main/java/org/webbitserver/helpers/UTF8Output.java
 * 
 * Which is an adaptation of:
 * 
 *     http://bjoern.hoehrmann.de/utf-8/decoder/dfa/
 * 
 * Copyright (c) 2008-2009 Bjoern Hoehrmann <bjoern@hoehrmann.de>
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package serverx.utils;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

/**
 * UTF8.
 */
public class UTF8 {
    /** The Constant UTF8_ACCEPT. */
    private static final int UTF8_ACCEPT = 0;

    /** The Constant UTF8_REJECT. */
    private static final int UTF8_REJECT = 12;

    /** The Constant TYPES. */
    private static final byte[] TYPES = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 8, 8, 2, 2, 2, 2, 2, 2,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 10, 3, 3, 3, 3, 3, 3, 3, 3, 3,
            3, 3, 3, 4, 3, 3, 11, 6, 6, 6, 5, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8 };

    /** The Constant STATES. */
    private static final byte[] STATES = { 0, 12, 24, 36, 60, 96, 84, 12, 12, 12, 48, 72, 12, 12, 12, 12, 12, 12,
            12, 12, 12, 12, 12, 12, 12, 0, 12, 12, 12, 12, 12, 0, 12, 0, 12, 12, 12, 24, 12, 12, 12, 12, 12, 24, 12,
            24, 12, 12, 12, 12, 12, 12, 12, 12, 12, 24, 12, 12, 12, 12, 12, 24, 12, 12, 12, 12, 12, 12, 12, 24, 12,
            12, 12, 12, 12, 12, 12, 12, 12, 36, 12, 36, 12, 12, 12, 36, 12, 12, 12, 12, 12, 36, 12, 36, 12, 12, 12,
            36, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12 };

    /** The state. */
    private int state = UTF8_ACCEPT;

    /** The codep. */
    private int codep = 0;

    /** The string builder. */
    private final StringBuilder stringBuilder = new StringBuilder();

    /**
     * The Class UTF8Exception.
     */
    public class UTF8Exception extends UnsupportedEncodingException {
        /** The Constant serialVersionUID. */
        private static final long serialVersionUID = 1L;

        /**
         * Instantiates a new UTF 8 exception.
         *
         * @param reason
         *            the reason
         */
        public UTF8Exception(final String reason) {
            super(reason);
        }

        /**
         * Instantiates a new UTF 8 exception.
         *
         * @param e
         *            the e
         */
        public UTF8Exception(final Exception e) {
            this(e.getMessage());
        }
    }

    /**
     * Append.
     *
     * @param bytes
     *            the bytes
     * @throws UTF8Exception
     *             the UTF 8 exception
     */
    public void append(final byte[] bytes) throws UTF8Exception {
        for (int i = 0; i < bytes.length; i++) {
            append(bytes[i]);
        }
    }

    /**
     * Append.
     *
     * @param b
     *            the b
     * @throws UTF8Exception
     *             the UTF 8 exception
     */
    public void append(final int b) throws UTF8Exception {
        final byte type = TYPES[b & 0xFF];

        codep = (state != UTF8_ACCEPT) ? (b & 0x3f) | (codep << 6) : (0xff >> type) & (b);

        state = STATES[state + type];

        if (state == UTF8_ACCEPT) {
            // See http://goo.gl/JdIVSu
            if (codep < Character.MIN_HIGH_SURROGATE) {
                stringBuilder.append((char) codep);
            } else {
                for (final char c : Character.toChars(codep)) {
                    stringBuilder.append(c);
                }
            }
        } else if (state == UTF8_REJECT) {
            throw new UTF8Exception("bytes are not UTF-8");
        }
    }

    /**
     * Gets the string and recycle.
     *
     * @return the string and recycle
     * @throws UTF8Exception
     *             the UTF 8 exception
     */
    public String getStringAndRecycle() throws UTF8Exception {
        if (state == UTF8_ACCEPT) {
            final String string = stringBuilder.toString();
            stringBuilder.setLength(0);
            return string;
        } else {
            throw new UTF8Exception("bytes are not UTF-8");
        }
    }

    /**
     * Utf 8 to string.
     *
     * @param bytes
     *            the bytes
     * @return the string
     * @throws UTF8Exception
     *             the UTF 8 exception
     */
    public static String utf8ToString(final byte[] bytes) throws UTF8Exception {
        final UTF8 decoder = new UTF8();
        decoder.append(bytes);
        return decoder.getStringAndRecycle();
    }

    /**
     * String to UTF 8.
     *
     * @param str
     *            the str
     * @return the byte[]
     */
    public static byte[] stringToUTF8(final String str) {
        return str.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * String to UTF 8 byte buf.
     *
     * @param str
     *            the str
     * @return the byte buf
     */
    public static ByteBuf stringToUTF8ByteBuf(final String str) {
        final ByteBuf byteBuf = Unpooled.buffer(str.length() * 2);
        byteBuf.writeBytes(stringToUTF8(str));
        return byteBuf;
    }

    /**
     * String to UTF 8 byte buf.
     *
     * @param str
     *            the str
     * @param ctx
     *            the ctx
     * @return the byte buf
     */
    public static ByteBuf stringToUTF8ByteBuf(final String str, final ChannelHandlerContext ctx) {
        final ByteBuf byteBuf = ctx.alloc().buffer(str.length() * 2);
        byteBuf.writeBytes(stringToUTF8(str));
        return byteBuf;
    }
}
