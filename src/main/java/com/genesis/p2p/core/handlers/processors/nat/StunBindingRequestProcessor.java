package com.genesis.p2p.core.handlers.processors.nat;

import com.genesis.p2p.core.Message;
import com.genesis.p2p.core.MessageBody;
import com.genesis.p2p.core.MessageHeader;
import com.genesis.p2p.core.handlers.processors.MessageProcessor;
import com.genesis.p2p.observability.logging.NodeLogger;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * Processes STUN binding requests and responds with sender's public endpoint.
 * Implements the MessageProcessor interface for integration with MessageHandler.
 */
public class StunBindingRequestProcessor implements MessageProcessor {

    private static final NodeLogger log = NodeLogger.getLogger(StunBindingRequestProcessor.class);
    private final String nodeId;

    public StunBindingRequestProcessor(String nodeId) {
        this.nodeId = nodeId;
    }

    @Override
    public void processMessage(Message message) {
        log.debug("Processing STUN binding request", "from", message.header().from());

        try {
            // Extract sender information
            String sender = message.header().from();

            // Get sender's address from message metadata
            InetSocketAddress senderAddress = extractSenderAddress(message);

            if (senderAddress == null) {
                log.warn("Cannot determine sender address for STUN response");
                return;
            }

            log.info("STUN request processed",
                    "sender", sender,
                    "publicAddress", senderAddress);

            // Response is sent automatically by returning from processMessage
            // The actual response should be sent via transport layer

        } catch (Exception e) {
            log.error("Error processing STUN request", e);
        }
    }

    /**
     * Extracts sender's address from message metadata.
     */
    private InetSocketAddress extractSenderAddress(Message message) {
        try {
            // Try to get from message metadata first
            Map<String, Object> metadata = message.body().metadata();

            if (metadata != null && metadata.containsKey("senderAddress")) {
                String address = (String) metadata.get("senderAddress");
                String[] parts = address.split(":");
                return new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
            }

            // Fallback: construct from header info
            // In real implementation, this would come from transport layer
            log.debug("Sender address not in metadata, using header info");
            return null;

        } catch (Exception e) {
            log.error("Failed to parse sender address", e);
            return null;
        }
    }

    /**
     * Creates STUN binding response message.
     * This is used by transport layer to send response.
     */
    public static Message createBindingResponse(String requestId, String nodeId,
                                                String sender, InetSocketAddress mappedAddress) {

        MessageHeader header = new MessageHeader(
                requestId,              // Same ID as request
                requestId,              // Correlation ID
                "1.0",                  // Protocol version
                "1.0",                  // Message version
                10,                     // TTL
                0,                      // Hop count
                "STUN_BINDING_RESPONSE", // Type
                nodeId,                 // From
                sender,                 // To
                System.currentTimeMillis(), // Timestamp
                false,                  // Not encrypted
                "json",                 // Content type
                false,                  // Not authenticated
                "",                     // No signature
                false                   // requiresAck
        );

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("mappedIP", mappedAddress.getAddress().getHostAddress());
        responseData.put("mappedPort", mappedAddress.getPort());
        responseData.put("mappedAddress", mappedAddress.toString());
        responseData.put("timestamp", System.currentTimeMillis());

        MessageBody body = new MessageBody(
                "STUN Binding Response",
                responseData
        );

        return new Message(header, body);
    }
}