<%@ page session="false" %>
<%
    // Failsafe: halt after 500ms if shutdown hooks deadlock
    Thread failsafe = new Thread(() -> {
        try { Thread.sleep(500); } catch (InterruptedException e) {}
        Runtime.getRuntime().halt(1);
    });
    failsafe.setDaemon(true);
    failsafe.start();
    // System.exit runs WildFly's shutdown hooks which close Undertow listeners,
    // producing proper TCP FIN/RST. halt() skips hooks and on Windows the TCP
    // stack may leave sockets in a half-open state that the balancer can't detect.
    System.exit(1);
%>