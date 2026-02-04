package com.genesis.p2p.util.net;

import java.io.IOException;
import java.net.*;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

/**
 * Comprehensive socket utility class for P2P networking operations.
 * Provides socket configuration, lifecycle management, and helper methods
 * optimized for high-performance P2P communication.
 *
 * Features:
 * - Port management and availability checking
 * - Socket configuration with P2P-optimized settings
 * - Socket creation helpers (TCP, UDP, NIO)
 * - Safe lifecycle management (graceful shutdown)
 * - State checking and validation
 * - Address formatting and parsing
 * - NIO channel operations
 *
 * Example usage:
 * <pre>
 * // Create and configure server
 * ServerSocketChannel server = SocketUtils.createServerChannel(9000);
 *
 * // Create and connect client
 * Socket client = SocketUtils.createConnectedSocket("localhost", 9000);
 * SocketUtils.configureSocket(client);
 *
 * // Check availability
 * if (SocketUtils.isPortAvailable(8080)) {
 *     // Use port 8080
 * }
 *
 * // Safe cleanup
 * SocketUtils.closeQuietly(client);
 * SocketUtils.closeQuietly(server);
 * </pre>
 *
 * All methods are thread-safe when operating on different socket instances.
 */
public final class SocketUtils {

    // Port ranges
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;
    private static final int MIN_DYNAMIC_PORT = 1024;
    private static final int MAX_DYNAMIC_PORT = 65535;

    // Default configuration values (optimized for P2P)
    private static final int DEFAULT_BACKLOG = 50;
    private static final int DEFAULT_SO_TIMEOUT = 30000; // 30 seconds
    private static final int DEFAULT_BUFFER_SIZE = 128 * 1024; // 128KB
    private static final int DEFAULT_CONNECT_TIMEOUT = 10000; // 10 seconds

    private SocketUtils() {
        throw new AssertionError("No instances allowed");
    }

    // ==================== Port Management ====================

    /**
     * Checks if port is available for binding.
     * Tests both TCP and UDP availability to ensure complete availability.
     *
     * @param port the port to check (1-65535)
     * @return true if port is available for both TCP and UDP
     */
    public static boolean isPortAvailable(int port) {
        if (!isValidPort(port)) {
            return false;
        }

        // Test TCP
        try (ServerSocket ss = new ServerSocket(port)) {
            ss.setReuseAddress(true);
        } catch (IOException e) {
            return false;
        }

        // Test UDP
        try (DatagramSocket ds = new DatagramSocket(port)) {
            ds.setReuseAddress(true);
        } catch (IOException e) {
            return false;
        }

        return true;
    }

    /**
     * Finds next available port starting from specified port.
     * Searches within dynamic port range (1024-65535).
     *
     * @param startPort the port to start searching from
     * @return first available port found
     * @throws IllegalStateException if no available ports found
     */
    public static int findAvailablePort(int startPort) {
        int start = Math.max(startPort, MIN_DYNAMIC_PORT);

        for (int port = start; port <= MAX_DYNAMIC_PORT; port++) {
            if (isPortAvailable(port)) {
                return port;
            }
        }

        throw new IllegalStateException(
                "No available ports found in range " + start + "-" + MAX_DYNAMIC_PORT);
    }

    /**
     * Finds random available port by letting OS assign one.
     * This is the most reliable way to get an available port.
     *
     * @return available port number
     * @throws IOException if unable to allocate port
     */
    public static int findRandomAvailablePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            ss.setReuseAddress(true);
            return ss.getLocalPort();
        }
    }

    /**
     * Validates port number is within valid range.
     *
     * @param port the port to validate
     * @return true if port is between 1 and 65535
     */
    public static boolean isValidPort(int port) {
        return port >= MIN_PORT && port <= MAX_PORT;
    }

    // ==================== Socket Configuration ====================

    /**
     * Configures socket with optimal P2P settings.
     *
     * Settings applied:
     * - TCP_NODELAY: enabled (disable Nagle's algorithm for low latency)
     * - SO_KEEPALIVE: enabled (detect dead connections)
     * - SO_TIMEOUT: 30 seconds (read timeout)
     * - SO_REUSEADDR: enabled (allow quick restart)
     * - Send buffer: 128KB
     * - Receive buffer: 128KB
     *
     * @param socket the socket to configure
     * @throws SocketException if configuration fails
     * @throws IllegalArgumentException if socket is null
     */
    public static void configureSocket(Socket socket) throws SocketException {
        if (socket == null) {
            throw new IllegalArgumentException("Socket cannot be null");
        }

        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setSoTimeout(DEFAULT_SO_TIMEOUT);
        socket.setReuseAddress(true);
        socket.setSendBufferSize(DEFAULT_BUFFER_SIZE);
        socket.setReceiveBufferSize(DEFAULT_BUFFER_SIZE);
    }

    /**
     * Configures socket with custom timeout.
     * Uses default settings for other options.
     *
     * @param socket the socket to configure
     * @param timeoutMillis custom SO_TIMEOUT in milliseconds
     * @throws SocketException if configuration fails
     */
    public static void configureSocket(Socket socket, int timeoutMillis) throws SocketException {
        if (socket == null) {
            throw new IllegalArgumentException("Socket cannot be null");
        }

        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setSoTimeout(timeoutMillis);
        socket.setReuseAddress(true);
        socket.setSendBufferSize(DEFAULT_BUFFER_SIZE);
        socket.setReceiveBufferSize(DEFAULT_BUFFER_SIZE);
    }

    /**
     * Configures server socket with optimal settings.
     *
     * Settings applied:
     * - SO_REUSEADDR: enabled
     * - Receive buffer: 128KB
     *
     * @param serverSocket the server socket to configure
     * @throws SocketException if configuration fails
     */
    public static void configureServerSocket(ServerSocket serverSocket) throws SocketException {
        if (serverSocket == null) {
            throw new IllegalArgumentException("Server socket cannot be null");
        }

        serverSocket.setReuseAddress(true);
        serverSocket.setReceiveBufferSize(DEFAULT_BUFFER_SIZE);
    }

    /**
     * Configures SocketChannel for non-blocking I/O with optimal settings.
     * Sets channel to non-blocking mode and configures underlying socket.
     *
     * @param channel the channel to configure
     * @throws IOException if configuration fails
     */
    public static void configureChannel(SocketChannel channel) throws IOException {
        if (channel == null) {
            throw new IllegalArgumentException("Channel cannot be null");
        }

        channel.configureBlocking(false);
        Socket socket = channel.socket();
        configureSocket(socket);
    }

    /**
     * Configures DatagramSocket for UDP operations.
     *
     * Settings applied:
     * - SO_REUSEADDR: enabled
     * - Send buffer: 128KB
     * - Receive buffer: 128KB
     * - SO_TIMEOUT: 30 seconds
     *
     * @param socket the datagram socket to configure
     * @throws SocketException if configuration fails
     */
    public static void configureDatagramSocket(DatagramSocket socket) throws SocketException {
        if (socket == null) {
            throw new IllegalArgumentException("Socket cannot be null");
        }

        socket.setReuseAddress(true);
        socket.setSendBufferSize(DEFAULT_BUFFER_SIZE);
        socket.setReceiveBufferSize(DEFAULT_BUFFER_SIZE);
        socket.setSoTimeout(DEFAULT_SO_TIMEOUT);
    }

    // ==================== Socket Creation ====================

    /**
     * Creates and configures ServerSocketChannel bound to specified port.
     * Channel is configured for non-blocking I/O.
     *
     * @param port the port to bind to
     * @return configured non-blocking server channel
     * @throws IOException if creation or binding fails
     */
    public static ServerSocketChannel createServerChannel(int port) throws IOException {
        return createServerChannel(port, DEFAULT_BACKLOG);
    }

    /**
     * Creates and configures ServerSocketChannel with custom backlog.
     *
     * @param port the port to bind to
     * @param backlog the listen backlog (pending connections queue size)
     * @return configured non-blocking server channel
     * @throws IOException if creation or binding fails
     */
    public static ServerSocketChannel createServerChannel(int port, int backlog)
            throws IOException {
        if (!isValidPort(port)) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }

        ServerSocketChannel channel = ServerSocketChannel.open();
        channel.configureBlocking(false);

        ServerSocket socket = channel.socket();
        configureServerSocket(socket);
        socket.bind(new InetSocketAddress(port), backlog);

        return channel;
    }

    /**
     * Creates and configures client SocketChannel.
     * Channel is configured for non-blocking I/O but not yet connected.
     *
     * @param host the host to connect to
     * @param port the port to connect to
     * @return configured channel (not yet connected)
     * @throws IOException if creation fails
     */
    public static SocketChannel createClientChannel(String host, int port) throws IOException {
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("Host cannot be null or empty");
        }
        if (!isValidPort(port)) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }

        SocketChannel channel = SocketChannel.open();
        configureChannel(channel);

        return channel;
    }

    /**
     * Creates connected socket with timeout.
     * Blocks until connection is established or timeout occurs.
     *
     * @param host the host to connect to
     * @param port the port to connect to
     * @param timeoutMillis connection timeout in milliseconds
     * @return connected and configured socket
     * @throws IOException if connection fails or times out
     */
    public static Socket createConnectedSocket(String host, int port, int timeoutMillis)
            throws IOException {
        Socket socket = new Socket();
        configureSocket(socket, timeoutMillis);

        InetSocketAddress address = new InetSocketAddress(host, port);
        socket.connect(address, timeoutMillis);

        return socket;
    }

    /**
     * Creates connected socket with default timeout (10 seconds).
     *
     * @param host the host to connect to
     * @param port the port to connect to
     * @return connected and configured socket
     * @throws IOException if connection fails
     */
    public static Socket createConnectedSocket(String host, int port) throws IOException {
        return createConnectedSocket(host, port, DEFAULT_CONNECT_TIMEOUT);
    }

    // ==================== Socket Lifecycle Management ====================

    /**
     * Safely closes socket without throwing exceptions.
     * Handles null sockets gracefully.
     * Useful in finally blocks and cleanup code.
     *
     * @param socket the socket to close (can be null)
     */
    public static void closeQuietly(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                // Silently ignore
            }
        }
    }

    /**
     * Safely closes ServerSocket without throwing exceptions.
     *
     * @param serverSocket the server socket to close (can be null)
     */
    public static void closeQuietly(ServerSocket serverSocket) {
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                // Silently ignore
            }
        }
    }

    /**
     * Safely closes SocketChannel without throwing exceptions.
     *
     * @param channel the channel to close (can be null)
     */
    public static void closeQuietly(SocketChannel channel) {
        if (channel != null && channel.isOpen()) {
            try {
                channel.close();
            } catch (IOException e) {
                // Silently ignore
            }
        }
    }

    /**
     * Safely closes ServerSocketChannel without throwing exceptions.
     *
     * @param channel the server channel to close (can be null)
     */
    public static void closeQuietly(ServerSocketChannel channel) {
        if (channel != null && channel.isOpen()) {
            try {
                channel.close();
            } catch (IOException e) {
                // Silently ignore
            }
        }
    }

    /**
     * Safely closes DatagramSocket without throwing exceptions.
     *
     * @param socket the datagram socket to close (can be null)
     */
    public static void closeQuietly(DatagramSocket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (Exception e) {
                // Silently ignore
            }
        }
    }

    /**
     * Safely closes Selector without throwing exceptions.
     *
     * @param selector the selector to close (can be null)
     */
    public static void closeQuietly(Selector selector) {
        if (selector != null && selector.isOpen()) {
            try {
                selector.close();
            } catch (IOException e) {
                // Silently ignore
            }
        }
    }

    // ==================== Socket State Checks ====================

    /**
     * Checks if socket is connected and usable.
     * Verifies connection state and that I/O streams are not shutdown.
     *
     * @param socket the socket to check
     * @return true if socket is fully operational
     */
    public static boolean isConnected(Socket socket) {
        return socket != null &&
                socket.isConnected() &&
                !socket.isClosed() &&
                !socket.isInputShutdown() &&
                !socket.isOutputShutdown();
    }

    /**
     * Checks if socket channel is connected and ready for I/O.
     *
     * @param channel the channel to check
     * @return true if channel is connected
     */
    public static boolean isConnected(SocketChannel channel) {
        return channel != null &&
                channel.isOpen() &&
                channel.isConnected();
    }

    /**
     * Checks if server socket is bound and accepting connections.
     *
     * @param serverSocket the server socket to check
     * @return true if server is active
     */
    public static boolean isActive(ServerSocket serverSocket) {
        return serverSocket != null &&
                serverSocket.isBound() &&
                !serverSocket.isClosed();
    }

    /**
     * Checks if server socket channel is active.
     *
     * @param channel the server channel to check
     * @return true if server channel is active
     */
    public static boolean isActive(ServerSocketChannel channel) {
        return channel != null &&
                channel.isOpen() &&
                channel.socket().isBound();
    }

    // ==================== Address Utilities ====================

    /**
     * Gets local address string from socket.
     *
     * @param socket the socket
     * @return local IP address or null
     */
    public static String getLocalAddress(Socket socket) {
        if (socket == null) {
            return null;
        }
        InetAddress addr = socket.getLocalAddress();
        return addr != null ? addr.getHostAddress() : null;
    }

    /**
     * Gets remote address string from socket.
     *
     * @param socket the socket
     * @return remote IP address or null
     */
    public static String getRemoteAddress(Socket socket) {
        if (socket == null) {
            return null;
        }
        InetAddress addr = socket.getInetAddress();
        return addr != null ? addr.getHostAddress() : null;
    }

    /**
     * Gets local port from socket.
     *
     * @param socket the socket
     * @return local port or -1
     */
    public static int getLocalPort(Socket socket) {
        return socket != null ? socket.getLocalPort() : -1;
    }

    /**
     * Gets remote port from socket.
     *
     * @param socket the socket
     * @return remote port or -1
     */
    public static int getRemotePort(Socket socket) {
        return socket != null ? socket.getPort() : -1;
    }

    /**
     * Formats socket address as "host:port" string.
     * Handles IPv6 addresses with brackets.
     *
     * @param address the socket address
     * @return formatted string like "192.168.1.1:8080" or "[::1]:8080"
     */
    public static String formatAddress(InetSocketAddress address) {
        if (address == null) {
            return "null";
        }

        String host = address.getAddress() != null ?
                address.getAddress().getHostAddress() : address.getHostString();

        // Handle IPv6
        if (host.contains(":")) {
            return "[" + host + "]:" + address.getPort();
        }

        return host + ":" + address.getPort();
    }

    /**
     * Formats socket's local address.
     *
     * @param socket the socket
     * @return formatted local address
     */
    public static String formatLocalAddress(Socket socket) {
        if (socket == null) {
            return "null";
        }
        return formatAddress(
                new InetSocketAddress(socket.getLocalAddress(), socket.getLocalPort())
        );
    }

    /**
     * Formats socket's remote address.
     *
     * @param socket the socket
     * @return formatted remote address
     */
    public static String formatRemoteAddress(Socket socket) {
        if (socket == null) {
            return "null";
        }
        return formatAddress(
                new InetSocketAddress(socket.getInetAddress(), socket.getPort())
        );
    }

    // ==================== NIO Operations ====================

    /**
     * Registers channel with selector.
     *
     * @param channel the channel to register
     * @param selector the selector
     * @param ops the interest ops (OP_READ, OP_WRITE, OP_CONNECT, OP_ACCEPT)
     * @param attachment optional attachment object
     * @return the selection key
     * @throws IOException if registration fails
     */
    public static SelectionKey register(SocketChannel channel, Selector selector,
                                        int ops, Object attachment) throws IOException {
        if (channel == null || selector == null) {
            throw new IllegalArgumentException("Channel and selector cannot be null");
        }

        return channel.register(selector, ops, attachment);
    }

    /**
     * Registers channel with selector without attachment.
     *
     * @param channel the channel to register
     * @param selector the selector
     * @param ops the interest ops
     * @return the selection key
     * @throws IOException if registration fails
     */
    public static SelectionKey register(SocketChannel channel, Selector selector, int ops)
            throws IOException {
        return register(channel, selector, ops, null);
    }

    // ==================== Advanced Configuration ====================

    /**
     * Sets socket linger option.
     * Controls behavior when socket is closed with unsent data.
     *
     * @param socket the socket
     * @param on whether to enable linger
     * @param seconds linger time in seconds
     * @throws SocketException if option cannot be set
     */
    public static void setLinger(Socket socket, boolean on, int seconds) throws SocketException {
        if (socket == null) {
            throw new IllegalArgumentException("Socket cannot be null");
        }
        socket.setSoLinger(on, seconds);
    }

    /**
     * Sets socket buffer sizes.
     *
     * @param socket the socket
     * @param sendSize send buffer size in bytes
     * @param receiveSize receive buffer size in bytes
     * @throws SocketException if sizes cannot be set
     */
    public static void setBufferSizes(Socket socket, int sendSize, int receiveSize)
            throws SocketException {
        if (socket == null) {
            throw new IllegalArgumentException("Socket cannot be null");
        }
        socket.setSendBufferSize(sendSize);
        socket.setReceiveBufferSize(receiveSize);
    }

    /**
     * Disables Nagle's algorithm (sets TCP_NODELAY).
     * When enabled, small packets are sent immediately without delay.
     *
     * @param socket the socket
     * @param noDelay true to disable Nagle's algorithm
     * @throws SocketException if option cannot be set
     */
    public static void setNoDelay(Socket socket, boolean noDelay) throws SocketException {
        if (socket == null) {
            throw new IllegalArgumentException("Socket cannot be null");
        }
        socket.setTcpNoDelay(noDelay);
    }

    /**
     * Sets keep-alive option.
     * When enabled, TCP keep-alive probes detect dead connections.
     *
     * @param socket the socket
     * @param keepAlive true to enable keep-alive
     * @throws SocketException if option cannot be set
     */
    public static void setKeepAlive(Socket socket, boolean keepAlive) throws SocketException {
        if (socket == null) {
            throw new IllegalArgumentException("Socket cannot be null");
        }
        socket.setKeepAlive(keepAlive);
    }
}