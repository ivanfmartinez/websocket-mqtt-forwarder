package com.github.sylvek.wsmqttfwd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.Future;

/**
 * @author Sylvain Maucourt
 */
public class Server {

    private static final Logger LOG = LoggerFactory.getLogger(Server.class);

    private String host = "0.0.0.0";
    private int port = 8081;

    private String m_mqttBrokerHost = "localhost";
    private int m_mqttBrokerPort = 1883;
    private AuthenticationHandler.MqttListener m_mqttListerner = null;

    private EventLoopGroup m_workerGroup;
    private EventLoopGroup m_bossGroup;

    static class WebSocketFrameToByteBufDecoder extends MessageToMessageDecoder<BinaryWebSocketFrame> {

        @Override
        protected void decode(ChannelHandlerContext chc, BinaryWebSocketFrame frame, List<Object> out) throws Exception
        {
            //convert the frame to a ByteBuf
            ByteBuf bb = frame.content();
            bb.retain();
            out.add(bb);
        }
    }

    static class ByteBufToWebSocketFrameEncoder extends MessageToMessageEncoder<ByteBuf> {

        @Override
        protected void encode(ChannelHandlerContext chc, ByteBuf bb, List<Object> out) throws Exception
        {
            //convert the ByteBuf to a WebSocketFrame
            BinaryWebSocketFrame result = new BinaryWebSocketFrame();
            result.content().writeBytes(bb);
            out.add(result);
        }
    }

    public static void main(String[] args)
    {
        if (args.length != 3) {
            System.out.println("java -jar wsmqttfwd.jar 8081 localhost 1883");
            System.exit(1);
        }

        final Server server = new Server();
        server.initServer(Integer.parseInt(args[0]), args[1], Integer.parseInt(args[2]), null);
        server.startServer();
        System.out.println("Websocket to MQTT proxy started");
        //Bind a shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run()
            {
                server.stopServer();
            }
        });
    }

    protected void stopServer()
    {
        LOG.info("Server stopping...");

        Future workerWaiter = m_workerGroup.shutdownGracefully();
        Future bossWaiter = m_bossGroup.shutdownGracefully();

        try {
            workerWaiter.await(100);
        } catch (InterruptedException iex) {
            throw new IllegalStateException(iex);
        }

        try {
            bossWaiter.await(100);
        } catch (InterruptedException iex) {
            throw new IllegalStateException(iex);
        }

        LOG.info("Server stopped");
    }

    protected void initServer(final int port, final String mqttHost, final int mqttPort, final AuthenticationHandler.MqttListener mqttListener)
    {
        initServer(host, port, mqttHost, mqttPort, mqttListener);
    }
    
    protected void initServer(final String host, final int port, final String mqttHost, final int mqttPort, final AuthenticationHandler.MqttListener mqttListener)
    {
        this.host = host;
        this.port = port;
        this.m_mqttBrokerHost = mqttHost;
        this.m_mqttBrokerPort = mqttPort;
        this.m_mqttListerner = mqttListener;
    }


    protected void configurePipeline(final ChannelPipeline pipeline) 
    {
        pipeline.addLast("httpEncoder", new HttpResponseEncoder());
        pipeline.addLast("httpDecoder", new HttpRequestDecoder());
        pipeline.addLast("aggregator", new HttpObjectAggregator(65536));
        pipeline.addLast("webSocketHandler", new WebSocketServerProtocolHandler("/mqtt", "mqtt, mqttv3.1, mqttv3.1.1"));
        pipeline.addLast("ws2bytebufDecoder", new WebSocketFrameToByteBufDecoder());
        pipeline.addLast("bytebuf2wsEncoder", new ByteBufToWebSocketFrameEncoder());
        pipeline.addLast("filter", new AuthenticationHandler(m_mqttListerner));
        pipeline.addLast("forward", new ForwardToMQTTBrokerHandler(m_mqttBrokerHost, m_mqttBrokerPort));
    }
    
    protected void startServer()
    {
        LOG.info("Server starting...");
        ServerBootstrap b = new ServerBootstrap();
        m_bossGroup = new NioEventLoopGroup();
        m_workerGroup = new NioEventLoopGroup();
        b.group(m_bossGroup, m_workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) throws Exception
                    {
                        ChannelPipeline pipeline = ch.pipeline();
                        try {
                            configurePipeline(pipeline);
                        } catch (Throwable th) {
                            LOG.error("Severe error during pipeline creation", th);
                            throw th;
                        }
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true);
        try {
            // Bind and start to accept incoming connections.
            ChannelFuture f = b.bind(host, port);
            LOG.info("Server bond host: {}, port: {}", host, port);
            f.sync();
        } catch (InterruptedException ex) {
            LOG.error(null, ex);
        }
    }
}
