package org.jboss.modcluster.test.apps;

import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

/**
 * Simple WebSocket echo endpoint for testing WebSocket connections through the balancer.
 * Echoes received messages back with a prefix indicating the worker name.
 *
 * <p>This class is compiled as part of the test sources and packaged into a WAR
 * using ShrinkWrap for deployment to WildFly workers.</p>
 */
@ServerEndpoint("/echo")
public class EchoWebSocketEndpoint {

    /**
     * Sends a greeting when a WebSocket connection is opened.
     *
     * @param session the WebSocket session
     */
    @OnOpen
    public void onOpen(Session session) {
        String workerName = System.getProperty("jboss.node.name", "unknown");
        try {
            session.getBasicRemote().sendText("CONNECTED:" + workerName);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Echoes received messages back with the worker name prefix.
     *
     * @param message the received message
     * @param session the WebSocket session
     * @return the echo response with worker name
     */
    @OnMessage
    public String onMessage(String message, Session session) {
        String workerName = System.getProperty("jboss.node.name", "unknown");
        return "ECHO:" + workerName + ":" + message;
    }
}
