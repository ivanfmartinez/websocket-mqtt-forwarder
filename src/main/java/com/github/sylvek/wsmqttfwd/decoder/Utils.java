/*
 * Copyright (c) 2012-2015 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */
package com.github.sylvek.wsmqttfwd.decoder;

import java.io.UnsupportedEncodingException;

import io.netty.buffer.ByteBuf;

/**
 * @author andrea
 * @author Sylvain Maucourt
 */
public class Utils {

    public static final int MAX_LENGTH_LIMIT = 268435455;

    public static final byte VERSION_3_1 = 3;
    public static final byte VERSION_3_1_1 = 4;

    public static byte readMessageType(ByteBuf in)
    {
        byte h1 = in.readByte();
        byte messageType = (byte) ((h1 & 0x00F0) >> 4);
        return messageType;
    }

    public static boolean checkHeaderAvailability(ByteBuf in)
    {
        if (in.readableBytes() < 1) {
            return false;
        }
        //byte h1 = in.get();
        //byte messageType = (byte) ((h1 & 0x00F0) >> 4);
        in.skipBytes(1); //skip the messageType byte

        int remainingLength = Utils.decodeRemainingLength(in);
        if (remainingLength == -1) {
            return false;
        }

        //check remaining length
        if (in.readableBytes() < remainingLength) {
            return false;
        }

        //return messageType == type ? MessageDecoderResult.OK : MessageDecoderResult.NOT_OK;
        return true;
    }

    /**
     * Decode the variable remaining length as defined in MQTT v3.1 specification
     * (section 2.1).
     *
     * @return the decoded length or -1 if needed more data to decode the length field.
     */
    static int decodeRemainingLength(ByteBuf in)
    {
        int multiplier = 1;
        int value = 0;
        byte digit;
        do {
            if (in.readableBytes() < 1) {
                return -1;
            }
            digit = in.readByte();
            value += (digit & 0x7F) * multiplier;
            multiplier *= 128;
        } while ((digit & 0x80) != 0);
        return value;
    }

    /**
     * Load a string from the given buffer, reading first the two bytes of len
     * and then the UTF-8 bytes of the string.
     *
     * @return the decoded string or null if NEED_DATA
     */
    static String decodeString(ByteBuf in) throws UnsupportedEncodingException
    {
        return new String(readFixedLengthContent(in), "UTF-8");
    }

    /**
     * Read a byte array from the buffer, use two bytes as length information followed by length bytes.
     */
    static byte[] readFixedLengthContent(ByteBuf in) throws UnsupportedEncodingException
    {
        if (in.readableBytes() < 2) {
            return null;
        }
        int strLen = in.readUnsignedShort();
        if (in.readableBytes() < strLen) {
            return null;
        }
        byte[] strRaw = new byte[strLen];
        in.readBytes(strRaw);

        return strRaw;
    }

    /**
     * Return the number of bytes to encode the gicen remaining length value
     */
    static int numBytesToEncode(int len)
    {
        if (0 <= len && len <= 127) return 1;
        if (128 <= len && len <= 16383) return 2;
        if (16384 <= len && len <= 2097151) return 3;
        if (2097152 <= len && len <= 268435455) return 4;
        throw new IllegalArgumentException("value shoul be in the range [0..268435455]");
    }
}
