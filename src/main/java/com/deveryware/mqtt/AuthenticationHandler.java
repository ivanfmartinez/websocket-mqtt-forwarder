package com.deveryware.mqtt;

import com.deveryware.mqtt.decoder.ConnectDecoder;
import com.deveryware.mqtt.decoder.PublishDecoder;
import com.deveryware.mqtt.decoder.SubscribeDecoder;
import com.deveryware.mqtt.decoder.Utils;
import com.deveryware.mqtt.message.ConnectMessage;
import com.deveryware.mqtt.message.PublishMessage;
import com.deveryware.mqtt.message.SubscribeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.internal.StringUtil;

/**
 * @author Sylvain Maucourt
 */
public class AuthenticationHandler extends ChannelDuplexHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationHandler.class);

    private static final byte CONNECT = 0x1;
    private static final byte SUBSCRIBE = 0x8;
    private static final byte PING = 0xC;
    private static final byte PUBLISH = 0x3;

    private final MqttListener mqttListener;

    private String clientID;

    public AuthenticationHandler(MqttListener mqttListener)
    {
        this.mqttListener = mqttListener;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception
    {
        if (msg instanceof ByteBuf) {
            final ByteBuf in = (ByteBuf) msg;

            traceData(in);

            in.markReaderIndex();
            if (!Utils.checkHeaderAvailability(in)) {
                in.resetReaderIndex();
                return;
            }
            in.resetReaderIndex();

            byte messageType = Utils.readMessageType(in);
            LOG.debug("receive new message: {}", messageType);
            switch (messageType) {
                case CONNECT:
                    final ConnectMessage connectMessage = new ConnectDecoder().decode(ctx, in);
                    if (this.mqttListener != null && !this.mqttListener.checkClientID(connectMessage.getClientID())) {
                        LOG.debug("clientID {} not valid", connectMessage.getClientID());
                        ctx.close();
                    }
                    this.clientID = connectMessage.getClientID();
                    LOG.debug("new clientID {} connected", this.clientID);
                    break;
                case SUBSCRIBE:
                    final SubscribeMessage subscribeMessage = new SubscribeDecoder().decode(ctx, in);
                    if (this.mqttListener != null && !this.mqttListener.checkSubScribeTopic(this.clientID, subscribeMessage.topics())) {
                        LOG.debug("clientID {} and topic {} mismatch", this.clientID, subscribeMessage.topics());
                        ctx.close();
                    }
                    break;
                case PUBLISH:
                    final PublishMessage publishMessage = new PublishDecoder().decode(ctx, in);
                    if (this.mqttListener != null && !this.mqttListener.checkPublishTopic(this.clientID, publishMessage.getTopicName())) {
                        LOG.debug("clientID {} and topic {} mismatch", this.clientID, publishMessage.getTopicName());
                        ctx.close();
                    }
                    break;
                case PING:
                    if (this.mqttListener != null) {
                        this.mqttListener.onPing(this.clientID);
                    }
                    break;
                default:
                    break;
            }

            in.resetReaderIndex();
        }
        super.channelRead(ctx, msg);
    }

    private static void traceData(ByteBuf in)
    {
        int length = in.readableBytes();
        if (length > 0) {
            StringBuilder buf = new StringBuilder();
            buf.append("> ").append(length).append('B').append(StringUtil.NEWLINE);
            ByteBufUtil.appendPrettyHexDump(buf, in);
            LOG.debug(buf.toString());
        }
    }

    public interface MqttListener {
        boolean checkClientID(String clientID);

        boolean checkSubScribeTopic(String clientID, List<String> topic);

        boolean checkPublishTopic(String clientID, String topic);

        void onPing(String clientID);
    }
}
