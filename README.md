# Websocket MQTT Forwarder

Websocket MQTT Forwarder is a websocket Proxy for MQTT Brokers.
It allows you to wrap a MQTT TCP connection in a websocket connection.
Most of brokers offer a websocket layer but it is difficult to interact with it.

If you want to add a authentication or authorization layer you need to use specific plugins but you should have several limitations.

Example…

- You have to use a password file and needs to restart the broker each time you updates it.
- You can interact with a Redis database but you can not customize inputs.

With this implementation, you are able to customize the authentication by _matching the clientID_ and authorization by _matching the subscribed or published topic_.

DISCLAMER: this project contains part of [moquette.io](https://github.com/andsel/moquette) especially for decoding MQTT messages.

## How to construct it ?

```
$> mvn clean compile assembly:single
$> java -jar target/wsmqttfwd-0.1-SNAPSHOT-jar-with-dependencies.jar
```

## How to use it ?

You just have to implement a Server, init it and start it, like that:

```
final Server server = new Server();
server.initServer(8081, "localhost", 1883, null);
server.startServer();
```

You can use the [Paho JavaScript Client](https://eclipse.org/paho/clients/js/) or whatever. This wrapper is non-intrusive and do not change anything if the client
is allowed to communicate with the MQTT broker.

```
var client = new Paho.MQTT.Client("ws://localhost:8081/mqtt", "clientID"
client.connect({
        userName: "testuser",
        password: "passwd",
        onSuccess: function() {
          console.log("onSuccess => subscribe to sensors/#");
          client.subscribe("sensors/#");
        }
      });
```

## Security layers

The authentication layer checks the clientID that should be unique on the broker instance (see MQTT protocol reference).
userName and password is not checked (it could be by the broker).

The authorization layer checks the topic names and the clientID.

You just have to implement a _AuthenticationHandler.MqttListener_

```
private AuthenticationHandler.MqttListener m_mqttListerner = new AuthenticationHandler.MqttListener() {
        @Override
        public boolean checkClientID(String clientID)
        {
            return false;
        }

        @Override
        public boolean checkSubScribeTopic(String clientID, List<String> topic)
        {
            return false;
        }

        @Override
        public boolean checkPublishTopic(String clientID, String topic)
        {
            return false;
        }

        @Override
        public void onPing(String clientID)
        {

        }
    };
```

And insert it during the initialization on the server instance.

```
final Server server = new Server();
server.initServer(8081, "localhost", 1883, m_mqttListerner);
server.startServer();
```

## How that works?

Websocket MQTT Forwarder is a Netty proxy. It uses two handlers.

- AuthenticationHandler
- ForwardToMQTTBrokerHandler

The first one is in charge to check the authentication and authorization rights.
It dispatches the ping event too.

The second is in charge to forward the messages between your MQTT Broker and the
client.

You can easily customize it by using the pipelineChannel on Netty.

```
pipeline.addLast("httpEncoder", new HttpResponseEncoder());
                pipeline.addLast("httpDecoder", new HttpRequestDecoder());
                pipeline.addLast("aggregator", new HttpObjectAggregator(65536));
                pipeline.addLast("webSocketHandler", new WebSocketServerProtocolHandler("/mqtt", "mqtt, mqttv3.1, mqttv3.1.1"));
                pipeline.addLast("ws2bytebufDecoder", new WebSocketFrameToByteBufDecoder());
                pipeline.addLast("bytebuf2wsEncoder", new ByteBufToWebSocketFrameEncoder());
                pipeline.addLast("filter", new AuthenticationHandler(m_mqttListerner));
                pipeline.addLast("forward", new ForwardToMQTTBrokerHandler(m_mqttBrokerHost, m_mqttBrokerPort));
```
