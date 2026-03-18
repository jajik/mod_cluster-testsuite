<%@ page session="true" %>
<%@ page import="java.util.*" %>
<%
    String worker = System.getProperty("jboss.node.name");
    session.setAttribute("worker", worker);
    session.setAttribute("timestamp", System.currentTimeMillis());
%>
<html>
<head><title>Timeout Test</title></head>
<body>
<p><strong>Session ID:</strong> <%= session.getId() %></p>
<p><strong>Worker:</strong> <%= worker %></p>
<p><strong>Timestamp:</strong> <%= session.getAttribute("timestamp") %></p>
<p><strong>Max Inactive Interval:</strong> <%= session.getMaxInactiveInterval() %> seconds</p>
</body>
</html>