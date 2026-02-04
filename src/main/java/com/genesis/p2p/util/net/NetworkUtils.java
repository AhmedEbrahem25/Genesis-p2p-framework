package com.genesis.p2p.util.net;

import java.net.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Network utility functions for the Genesis P2P framework.
 *
 * Provides utilities for:
 * - Network interface discovery
 * - Address parsing and validation
 * - Local/external address detection
 * - Port availability checking
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public final class NetworkUtils {

    private NetworkUtils() {
        // Utility class - prevent instantiation
    }

    // ==================== Interface Discovery ====================

    /**
     * Gets all available network interfaces.
     *
     * @return list of network interfaces
     */
    public static List<NetworkInterface> getAllInterfaces() {
        try {
            return Collections.list(NetworkInterface.getNetworkInterfaces());
        } catch (SocketException e) {
            return Collections.emptyList();
        }
    }

    /**
     * Gets all active (up and running) network interfaces.
     *
     * @return list of active interfaces
     */
    public static List<NetworkInterface> getActiveInterfaces() {
        return getAllInterfaces().stream()
                .filter(NetworkUtils::isActive)
                .collect(Collectors.toList());
    }

    /**
     * Checks if a network interface is active.
     */
    private static boolean isActive(NetworkInterface iface) {
        try {
            return iface.isUp() && !iface.isLoopback() && !iface.isVirtual();
        } catch (SocketException e) {
            return false;
        }
    }

    // ==================== Address Discovery ====================

    /**
     * Gets all local IP addresses.
     *
     * @return list of local IP addresses
     */
    public static List<InetAddress> getAllLocalAddresses() {
        List<InetAddress> addresses = new ArrayList<>();
        for (NetworkInterface iface : getActiveInterfaces()) {
            addresses.addAll(Collections.list(iface.getInetAddresses()));
        }
        return addresses;
    }

    /**
     * Gets all IPv4 addresses.
     *
     * @return list of IPv4 addresses
     */
    public static List<Inet4Address> getIPv4Addresses() {
        return getAllLocalAddresses().stream()
                .filter(addr -> addr instanceof Inet4Address)
                .map(addr -> (Inet4Address) addr)
                .filter(addr -> !addr.isLoopbackAddress())
                .collect(Collectors.toList());
    }

    /**
     * Gets all IPv6 addresses.
     *
     * @return list of IPv6 addresses
     */
    public static List<Inet6Address> getIPv6Addresses() {
        return getAllLocalAddresses().stream()
                .filter(addr -> addr instanceof Inet6Address)
                .map(addr -> (Inet6Address) addr)
                .filter(addr -> !addr.isLoopbackAddress())
                .collect(Collectors.toList());
    }

    /**
     * Gets the primary local address (best guess for external connectivity).
     *
     * @return primary local address or loopback if none found
     */
    public static InetAddress getPrimaryLocalAddress() {
        List<Inet4Address> ipv4 = getIPv4Addresses();

        // Prefer non-private addresses
        Optional<Inet4Address> publicAddr = ipv4.stream()
                .filter(addr -> !isPrivateAddress(addr))
                .findFirst();
        if (publicAddr.isPresent()) {
            return publicAddr.get();
        }

        // Then try any IPv4
        if (!ipv4.isEmpty()) {
            return ipv4.get(0);
        }

        // Fallback to IPv6
        List<Inet6Address> ipv6 = getIPv6Addresses();
        if (!ipv6.isEmpty()) {
            return ipv6.get(0);
        }

        // Last resort: loopback
        return InetAddress.getLoopbackAddress();
    }

    // ==================== Address Classification ====================

    /**
     * Checks if an address is a private (non-routable) address.
     *
     * Private ranges:
     * - 10.0.0.0/8
     * - 172.16.0.0/12
     * - 192.168.0.0/16
     */
    public static boolean isPrivateAddress(InetAddress address) {
        if (address == null) {
            return false;
        }

        byte[] addr = address.getAddress();
        if (addr.length != 4) {
            // Not IPv4
            return false;
        }

        int b0 = addr[0] & 0xFF;
        int b1 = addr[1] & 0xFF;

        // 10.0.0.0/8
        if (b0 == 10) {
            return true;
        }

        // 172.16.0.0/12
        if (b0 == 172 && (b1 >= 16 && b1 <= 31)) {
            return true;
        }

        // 192.168.0.0/16
        if (b0 == 192 && b1 == 168) {
            return true;
        }

        return false;
    }

    /**
     * Checks if an address is a link-local address.
     *
     * Link-local range: 169.254.0.0/16
     */
    public static boolean isLinkLocalAddress(InetAddress address) {
        return address != null && address.isLinkLocalAddress();
    }

    /**
     * Checks if an address is a multicast address.
     */
    public static boolean isMulticastAddress(InetAddress address) {
        return address != null && address.isMulticastAddress();
    }

    /**
     * Checks if an address is routable (not private, link-local, or loopback).
     */
    public static boolean isRoutableAddress(InetAddress address) {
        if (address == null) {
            return false;
        }
        return !address.isLoopbackAddress() &&
                !address.isLinkLocalAddress() &&
                !isPrivateAddress(address);
    }

    // ==================== Port Utilities ====================

    /**
     * Checks if a port is available for binding.
     *
     * @param port the port to check
     * @return true if available
     */
    public static boolean isPortAvailable(int port) {
        return isPortAvailable(port, true) && isPortAvailable(port, false);
    }

    /**
     * Checks if a TCP or UDP port is available.
     *
     * @param port  the port to check
     * @param isTcp true for TCP, false for UDP
     * @return true if available
     */
    public static boolean isPortAvailable(int port, boolean isTcp) {
        if (port < 0 || port > 65535) {
            return false;
        }

        if (isTcp) {
            try (ServerSocket socket = new ServerSocket(port)) {
                socket.setReuseAddress(true);
                return true;
            } catch (Exception e) {
                return false;
            }
        } else {
            try (DatagramSocket socket = new DatagramSocket(port)) {
                socket.setReuseAddress(true);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    /**
     * Finds an available port in the given range.
     *
     * @param startPort start of range (inclusive)
     * @param endPort   end of range (inclusive)
     * @return available port or -1 if none found
     */
    public static int findAvailablePort(int startPort, int endPort) {
        for (int port = startPort; port <= endPort; port++) {
            if (isPortAvailable(port)) {
                return port;
            }
        }
        return -1;
    }

    /**
     * Finds any available port.
     *
     * @return available port
     */
    public static int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            return -1;
        }
    }

    // ==================== Address Parsing ====================

    /**
     * Parses an address string into InetSocketAddress.
     *
     * Supported formats:
     * - "host:port"
     * - "host" (uses default port)
     * - "[ipv6]:port"
     *
     * @param address     address string
     * @param defaultPort default port if not specified
     * @return parsed socket address
     */
    public static InetSocketAddress parseAddress(String address, int defaultPort) {
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("Address cannot be null or empty");
        }

        String host;
        int port = defaultPort;

        // Handle IPv6 addresses
        if (address.startsWith("[")) {
            int closeBracket = address.indexOf(']');
            if (closeBracket == -1) {
                throw new IllegalArgumentException("Invalid IPv6 address format: " + address);
            }
            host = address.substring(1, closeBracket);
            if (address.length() > closeBracket + 1) {
                if (address.charAt(closeBracket + 1) != ':') {
                    throw new IllegalArgumentException("Invalid address format: " + address);
                }
                port = Integer.parseInt(address.substring(closeBracket + 2));
            }
        } else {
            // Handle IPv4 or hostname
            int colonIndex = address.lastIndexOf(':');
            if (colonIndex != -1) {
                host = address.substring(0, colonIndex);
                port = Integer.parseInt(address.substring(colonIndex + 1));
            } else {
                host = address;
            }
        }

        return new InetSocketAddress(host, port);
    }

    /**
     * Formats a socket address as a string.
     */
    public static String formatAddress(InetSocketAddress address) {
        if (address == null) {
            return "null";
        }
        InetAddress inetAddr = address.getAddress();
        if (inetAddr instanceof Inet6Address) {
            return "[" + inetAddr.getHostAddress() + "]:" + address.getPort();
        }
        return inetAddr.getHostAddress() + ":" + address.getPort();
    }

    // ==================== Address Validation ====================

    /**
     * Validates an IP address string.
     *
     * @param address address string to validate
     * @return true if valid IP address
     */
    public static boolean isValidIpAddress(String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }
        try {
            InetAddress.getByName(address);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * Validates a port number.
     *
     * @param port port number
     * @return true if valid (1-65535)
     */
    public static boolean isValidPort(int port) {
        return port > 0 && port <= 65535;
    }

    /**
     * Validates a socket address.
     *
     * @param host host string
     * @param port port number
     * @return true if valid
     */
    public static boolean isValidSocketAddress(String host, int port) {
        return isValidIpAddress(host) && isValidPort(port);
    }

    // ==================== DNS ====================

    /**
     * Resolves a hostname to IP address.
     *
     * @param hostname hostname to resolve
     * @return resolved address or null if failed
     */
    public static InetAddress resolveHostname(String hostname) {
        try {
            return InetAddress.getByName(hostname);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * Resolves a hostname to all IP addresses.
     *
     * @param hostname hostname to resolve
     * @return list of addresses
     */
    public static List<InetAddress> resolveAllAddresses(String hostname) {
        try {
            return Arrays.asList(InetAddress.getAllByName(hostname));
        } catch (UnknownHostException e) {
            return Collections.emptyList();
        }
    }

    /**
     * Gets the local hostname.
     *
     * @return local hostname or "localhost" if unavailable
     */
    public static String getLocalHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }
}
