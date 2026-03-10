<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%
    String nodeName = System.getProperty("jboss.node.name", "unknown");
    String sessionId = session.getId();
%>
<!DOCTYPE html>
<html>
<head>
    <title>ModCluster Demo</title>
</head>
<body>
    <h1>ModCluster Test Application</h1>
    <p><strong>Worker:</strong> <%= nodeName %></p>
    <p><strong>Session ID:</strong> <%= sessionId %></p>
    <p><strong>Time:</strong> <%= new java.util.Date() %></p>
</body>
</html>
