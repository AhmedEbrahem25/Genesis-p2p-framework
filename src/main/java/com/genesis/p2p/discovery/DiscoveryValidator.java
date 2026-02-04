package com.genesis.p2p.discovery;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Validator for discovery configuration and addresses.
 */
public class DiscoveryValidator {

    /**
     * Validates a multicast address.
     *
     * @param address the address to validate
     * @return true if valid multicast address
     */
    public static boolean isValidMulticastAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return false;
        }

        try {
            InetAddress addr = InetAddress.getByName(address);
            return addr.isMulticastAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * Validates a port number.
     *
     * @param port the port to validate
     * @return true if valid port (1-65535)
     */
    public static boolean isValidPort(int port) {
        return port > 0 && port <= 65535;
    }

    /**
     * Validates multicast group and port combination.
     *
     * @param group multicast group address
     * @param port multicast port
     * @throws IllegalArgumentException if validation fails
     */
    public static void validateMulticastConfig(String group, int port) {
        if (!isValidMulticastAddress(group)) {
            throw new IllegalArgumentException(
                    "Invalid multicast address: " + group + ". " +
                            "Multicast addresses must be in range 224.0.0.0 to 239.255.255.255"
            );
        }

        if (!isValidPort(port)) {
            throw new IllegalArgumentException(
                    "Invalid port: " + port + ". Port must be between 1 and 65535"
            );
        }
    }

    /**
     * Validates broadcast configuration.
     *
     * @param port broadcast port
     * @throws IllegalArgumentException if validation fails
     */
    public static void validateBroadcastConfig(int port) {
        if (!isValidPort(port)) {
            throw new IllegalArgumentException(
                    "Invalid broadcast port: " + port + ". Port must be between 1 and 65535"
            );
        }
    }

    /**
     * Validates bootstrap address format (ip:port or hostname:port).
     *
     * @param address bootstrap address
     * @return true if valid format
     */
    public static boolean isValidBootstrapAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return false;
        }

        String[] parts = address.split(":");
        if (parts.length != 2) {
            return false;
        }

        try {
            int port = Integer.parseInt(parts[1].trim());
            return isValidPort(port);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Validates a list of bootstrap addresses.
     *
     * @param addresses list of bootstrap addresses
     * @throws IllegalArgumentException if any address is invalid
     */
    public static void validateBootstrapAddresses(java.util.List<String> addresses) {
        if (addresses == null) {
            throw new IllegalArgumentException("Bootstrap addresses cannot be null");
        }

        for (String address : addresses) {
            if (!isValidBootstrapAddress(address)) {
                throw new IllegalArgumentException(
                        "Invalid bootstrap address format: " + address + ". " +
                                "Expected format: ip:port or hostname:port"
                );
            }
        }
    }
}



/**
 * Discovery exception hierarchy.
 */
class DiscoveryException extends Exception {
    public DiscoveryException(String message) {
        super(message);
    }

    public DiscoveryException(String message, Throwable cause) {
        super(message, cause);
    }
}

class MulticastInitException extends DiscoveryException {
    public MulticastInitException(String message, Throwable cause) {
        super("Failed to initialize multicast: " + message, cause);
    }
}

class BroadcastInitException extends DiscoveryException {
    public BroadcastInitException(String message, Throwable cause) {
        super("Failed to initialize broadcast: " + message, cause);
    }
}

class BootstrapConnectionException extends DiscoveryException {
    public BootstrapConnectionException(String message) {
        super("Bootstrap connection failed: " + message);
    }

    public BootstrapConnectionException(String message, Throwable cause) {
        super("Bootstrap connection failed: " + message, cause);
    }
}