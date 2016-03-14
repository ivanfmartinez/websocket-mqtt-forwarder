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
package com.deveryware.mqtt.decoder;

import com.deveryware.mqtt.message.AbstractMessage;
import com.deveryware.mqtt.message.ConnectMessage;

import java.io.UnsupportedEncodingException;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.AttributeMap;

/**
 * @author andrea
 * @author Sylvain Maucourt
 */
public class ConnectDecoder extends DemuxDecoder<ConnectMessage> {

    static final AttributeKey<Boolean> CONNECT_STATUS = AttributeKey.valueOf("connected");

    //3 = 3.1, 4 = 3.1.1
    static final AttributeKey<Integer> PROTOCOL_VERSION = AttributeKey.valueOf("version");

    @Override
    public ConnectMessage decode(AttributeMap ctx, ByteBuf in) throws UnsupportedEncodingException
    {
        in.resetReaderIndex();
        //Common decoding part
        ConnectMessage message = new ConnectMessage();
        if (!decodeCommonHeader(message, 0x00, in)) {
            in.resetReaderIndex();
            return null;
        }
        int remainingLength = message.getRemainingLength();
        int start = in.readerIndex();

        int protocolNameLen = in.readUnsignedShort();
        byte[] encProtoName;
        String protoName;
        Attribute<Integer> versionAttr = ctx.attr(PROTOCOL_VERSION);
        switch (protocolNameLen) {
            case 6:
                //MQTT version 3.1 "MQIsdp"
                //ProtocolName 8 bytes or 6 bytes
                if (in.readableBytes() < 10) {
                    in.resetReaderIndex();
                    return null;
                }

                encProtoName = new byte[6];
                in.readBytes(encProtoName);
                protoName = new String(encProtoName, "UTF-8");
                if (!"MQIsdp".equals(protoName)) {
                    in.resetReaderIndex();
                    throw new CorruptedFrameException("Invalid protoName: " + protoName);
                }
                message.setProtocolName(protoName);

                versionAttr.set((int) Utils.VERSION_3_1);
                break;
            case 4:
                //MQTT version 3.1.1 "MQTT"
                //ProtocolName 6 bytes
                if (in.readableBytes() < 8) {
                    in.resetReaderIndex();
                    return null;
                }
                encProtoName = new byte[4];
                in.readBytes(encProtoName);
                protoName = new String(encProtoName, "UTF-8");
                if (!"MQTT".equals(protoName)) {
                    in.resetReaderIndex();
                    throw new CorruptedFrameException("Invalid protoName: " + protoName);
                }
                message.setProtocolName(protoName);
                versionAttr.set((int) Utils.VERSION_3_1_1);
                break;
            default:
                //protocol broken
                throw new CorruptedFrameException("Invalid protoName size: " + protocolNameLen);
        }

        //ProtocolVersion 1 byte (value 0x03 for 3.1, 0x04 for 3.1.1)
        message.setProtocolVersion(in.readByte());
        if (message.getProtocolVersion() == Utils.VERSION_3_1_1) {
            //if 3.1.1, check the flags (dup, retain and qos == 0)
            if (message.isDupFlag() || message.isRetainFlag() || message.getQos() != AbstractMessage.QOSType.MOST_ONE) {
                throw new CorruptedFrameException("Received a CONNECT with fixed header flags != 0");
            }

            //check if this is another connect from the same client on the same session
            Attribute<Boolean> connectAttr = ctx.attr(ConnectDecoder.CONNECT_STATUS);
            Boolean alreadyConnected = connectAttr.get();
            if (alreadyConnected == null) {
                //never set
                connectAttr.set(true);
            } else if (alreadyConnected) {
                throw new CorruptedFrameException("Received a second CONNECT on the same network connection");
            }
        }

        //Connection flag
        byte connFlags = in.readByte();
        if (message.getProtocolVersion() == Utils.VERSION_3_1_1) {
            if ((connFlags & 0x01) != 0) { //bit(0) of connection flags is != 0
                throw new CorruptedFrameException("Received a CONNECT with connectionFlags[0(bit)] != 0");
            }
        }

        boolean cleanSession = ((connFlags & 0x02) >> 1) == 1;
        boolean willFlag = ((connFlags & 0x04) >> 2) == 1;
        byte willQos = (byte) ((connFlags & 0x18) >> 3);
        if (willQos > 2) {
            in.resetReaderIndex();
            throw new CorruptedFrameException("Expected will QoS in range 0..2 but found: " + willQos);
        }
        boolean willRetain = ((connFlags & 0x20) >> 5) == 1;
        boolean passwordFlag = ((connFlags & 0x40) >> 6) == 1;
        boolean userFlag = ((connFlags & 0x80) >> 7) == 1;
        //a password is true iff user is true.
        if (!userFlag && passwordFlag) {
            in.resetReaderIndex();
            throw new CorruptedFrameException("Expected password flag to true if the user flag is true but was: " + passwordFlag);
        }
        message.setCleanSession(cleanSession);
        message.setWillFlag(willFlag);
        message.setWillQos(willQos);
        message.setWillRetain(willRetain);
        message.setPasswordFlag(passwordFlag);
        message.setUserFlag(userFlag);

        //Keep Alive timer 2 bytes
        //int keepAlive = Utils.readWord(in);
        int keepAlive = in.readUnsignedShort();
        message.setKeepAlive(keepAlive);

        if ((remainingLength == 12 && message.getProtocolVersion() == Utils.VERSION_3_1) ||
                (remainingLength == 10 && message.getProtocolVersion() == Utils.VERSION_3_1_1)) {
            return message;
        }

        //Decode the ClientID
        String clientID = Utils.decodeString(in);
        if (clientID == null) {
            in.resetReaderIndex();
            return null;
        }
        message.setClientID(clientID);

        //Decode willTopic
        if (willFlag) {
            String willTopic = Utils.decodeString(in);
            if (willTopic == null) {
                in.resetReaderIndex();
                return null;
            }
            message.setWillTopic(willTopic);
        }

        //Decode willMessage
        if (willFlag) {
            byte[] willMessage = Utils.readFixedLengthContent(in);
            if (willMessage == null) {
                in.resetReaderIndex();
                return null;
            }
            message.setWillMessage(willMessage);
        }

        //Compatibility check with v3.0, remaining length has precedence over
        //the user and password flags
        int readed = in.readerIndex() - start;
        if (readed == remainingLength) {
            return message;
        }

        //Decode username
        if (userFlag) {
            String userName = Utils.decodeString(in);
            if (userName == null) {
                in.resetReaderIndex();
                return null;
            }
            message.setUsername(userName);
        }

        readed = in.readerIndex() - start;
        if (readed == remainingLength) {
            return message;
        }

        //Decode password
        if (passwordFlag) {
            byte[] password = Utils.readFixedLengthContent(in);
            if (password == null) {
                in.resetReaderIndex();
                return null;
            }
            message.setPassword(password);
        }

        return message;
    }
}
