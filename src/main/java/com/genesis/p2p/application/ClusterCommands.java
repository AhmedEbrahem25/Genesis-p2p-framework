package com.genesis.p2p.application;

import com.genesis.p2p.observability.logging.NodeLogger;
import com.genesis.p2p.util.common.Time;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * ClusterCommands - Command pattern implementation for cluster operations.
 *
 * Design Patterns Applied:
 * - Command Pattern: Encapsulates cluster operations as objects
 * - Memento Pattern: Captures state for undo/redo operations
 * - Composite Pattern: Macro commands that execute multiple commands
 *
 * Benefits:
 * - Undo/Redo support for cluster operations
 * - Command history and audit trail
 * - Queuing and scheduling of operations
 * - Transaction-like semantics
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class ClusterCommands {

    private static final NodeLogger log = NodeLogger.getLogger(ClusterCommands.class);

    // ==================== Command Pattern: Interface ====================

    /**
     * Command interface for cluster operations.
     * Command Pattern - Encapsulates an operation as an object.
     */
    public interface ClusterCommand {
        /**
         * Executes the command.
         *
         * @return command result
         */
        CommandResult execute();

        /**
         * Undoes the command if possible.
         *
         * @return true if undo was successful
         */
        default boolean undo() {
            return false; // Default: not undoable
        }

        /**
         * Checks if command is undoable.
         */
        default boolean isUndoable() {
            return false;
        }

        /**
         * Gets the command name.
         */
        String getName();

        /**
         * Gets command description.
         */
        default String getDescription() {
            return getName();
        }
    }

    /**
     * Command result.
     */
    public record CommandResult(
            boolean success,
            String message,
            Object data,
            Instant executedAt,
            long durationMs
    ) {
        public static CommandResult success(String message) {
            return new CommandResult(true, message, null, Instant.now(), 0);
        }

        public static CommandResult success(String message, Object data, long durationMs) {
            return new CommandResult(true, message, data, Instant.now(), durationMs);
        }

        public static CommandResult failure(String message) {
            return new CommandResult(false, message, null, Instant.now(), 0);
        }

        public static CommandResult failure(String message, Exception e) {
            return new CommandResult(false, message + ": " + e.getMessage(), e, Instant.now(), 0);
        }
    }

    // ==================== Concrete Commands ====================

    /**
     * Command to add a node to the cluster.
     */
    public static class AddNodeCommand implements ClusterCommand {
        private final ClusterManager cluster;
        private final NodeRuntime nodeRuntime;
        private boolean executed = false;

        public AddNodeCommand(ClusterManager cluster, NodeRuntime nodeRuntime) {
            this.cluster = cluster;
            this.nodeRuntime = nodeRuntime;
        }

        @Override
        public CommandResult execute() {
            long start = System.currentTimeMillis();
            try {
                cluster.addNode(nodeRuntime);
                executed = true;
                return CommandResult.success(
                        "Node added: " + nodeRuntime.getNode().getNodeId(),
                        nodeRuntime.getNode().getNodeId(),
                        System.currentTimeMillis() - start
                );
            } catch (Exception e) {
                return CommandResult.failure("Failed to add node", e);
            }
        }

        @Override
        public boolean undo() {
            if (!executed) return false;
            try {
                cluster.removeNode(nodeRuntime.getNode().getNodeId());
                executed = false;
                return true;
            } catch (Exception e) {
                log.error("Failed to undo AddNodeCommand", e);
                return false;
            }
        }

        @Override
        public boolean isUndoable() {
            return true;
        }

        @Override
        public String getName() {
            return "AddNode";
        }

        @Override
        public String getDescription() {
            return "Add node " + nodeRuntime.getNode().getNodeId() + " to cluster";
        }
    }

    /**
     * Command to remove a node from the cluster.
     */
    public static class RemoveNodeCommand implements ClusterCommand {
        private final ClusterManager cluster;
        private final String nodeId;
        private NodeRuntime removedNode;

        public RemoveNodeCommand(ClusterManager cluster, String nodeId) {
            this.cluster = cluster;
            this.nodeId = nodeId;
        }

        @Override
        public CommandResult execute() {
            long start = System.currentTimeMillis();
            try {
                removedNode = cluster.getNode(nodeId).orElse(null);
                if (removedNode == null) {
                    return CommandResult.failure("Node not found: " + nodeId);
                }
                cluster.removeNode(nodeId);
                return CommandResult.success(
                        "Node removed: " + nodeId,
                        nodeId,
                        System.currentTimeMillis() - start
                );
            } catch (Exception e) {
                return CommandResult.failure("Failed to remove node", e);
            }
        }

        @Override
        public boolean undo() {
            if (removedNode == null) return false;
            try {
                cluster.addNode(removedNode);
                return true;
            } catch (Exception e) {
                log.error("Failed to undo RemoveNodeCommand", e);
                return false;
            }
        }

        @Override
        public boolean isUndoable() {
            return true;
        }

        @Override
        public String getName() {
            return "RemoveNode";
        }

        @Override
        public String getDescription() {
            return "Remove node " + nodeId + " from cluster";
        }
    }

    /**
     * Command to start all nodes in the cluster.
     */
    public static class StartClusterCommand implements ClusterCommand {
        private final ClusterManager cluster;

        public StartClusterCommand(ClusterManager cluster) {
            this.cluster = cluster;
        }

        @Override
        public CommandResult execute() {
            long start = System.currentTimeMillis();
            try {
                cluster.startAll();
                return CommandResult.success(
                        "Cluster started",
                        cluster.getClusterSize(),
                        System.currentTimeMillis() - start
                );
            } catch (Exception e) {
                return CommandResult.failure("Failed to start cluster", e);
            }
        }

        @Override
        public boolean undo() {
            try {
                cluster.stopAll();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public boolean isUndoable() {
            return true;
        }

        @Override
        public String getName() {
            return "StartCluster";
        }
    }

    /**
     * Command to stop all nodes in the cluster.
     */
    public static class StopClusterCommand implements ClusterCommand {
        private final ClusterManager cluster;

        public StopClusterCommand(ClusterManager cluster) {
            this.cluster = cluster;
        }

        @Override
        public CommandResult execute() {
            long start = System.currentTimeMillis();
            try {
                cluster.stopAll();
                return CommandResult.success(
                        "Cluster stopped",
                        cluster.getClusterSize(),
                        System.currentTimeMillis() - start
                );
            } catch (Exception e) {
                return CommandResult.failure("Failed to stop cluster", e);
            }
        }

        @Override
        public String getName() {
            return "StopCluster";
        }
    }

    /**
     * Command to trigger leader election.
     */
    public static class ElectLeaderCommand implements ClusterCommand {
        private final ClusterManager cluster;
        private String previousLeader;

        public ElectLeaderCommand(ClusterManager cluster) {
            this.cluster = cluster;
        }

        @Override
        public CommandResult execute() {
            long start = System.currentTimeMillis();
            try {
                previousLeader = cluster.getLeaderId().orElse(null);
                cluster.electLeader();
                String newLeader = cluster.getLeaderId().orElse(null);
                return CommandResult.success(
                        "Leader elected: " + newLeader,
                        newLeader,
                        System.currentTimeMillis() - start
                );
            } catch (Exception e) {
                return CommandResult.failure("Failed to elect leader", e);
            }
        }

        @Override
        public String getName() {
            return "ElectLeader";
        }
    }

    // ==================== Composite Pattern: Macro Command ====================

    /**
     * Macro command that executes multiple commands in sequence.
     * Composite Pattern - Treats group of commands as single command.
     */
    public static class MacroCommand implements ClusterCommand {
        private final String name;
        private final List<ClusterCommand> commands;
        private final List<ClusterCommand> executedCommands;

        public MacroCommand(String name) {
            this.name = name;
            this.commands = new ArrayList<>();
            this.executedCommands = new ArrayList<>();
        }

        public MacroCommand add(ClusterCommand command) {
            commands.add(command);
            return this;
        }

        @Override
        public CommandResult execute() {
            long start = Time.currentMillis();
            StringBuilder messages = new StringBuilder();

            for (ClusterCommand command : commands) {
                CommandResult result = command.execute();
                executedCommands.add(command);

                if (!result.success()) {
                    // Rollback executed commands
                    undo();
                    return CommandResult.failure(
                            "Macro failed at " + command.getName() + ": " + result.message()
                    );
                }
                messages.append(command.getName()).append(": OK; ");
            }

            return CommandResult.success(
                    messages.toString(),
                    executedCommands.size(),
                    Time.currentMillis() - start
            );
        }

        @Override
        public boolean undo() {
            boolean allUndone = true;
            // Undo in reverse order
            for (int i = executedCommands.size() - 1; i >= 0; i--) {
                ClusterCommand cmd = executedCommands.get(i);
                if (cmd.isUndoable() && !cmd.undo()) {
                    allUndone = false;
                }
            }
            executedCommands.clear();
            return allUndone;
        }

        @Override
        public boolean isUndoable() {
            return commands.stream().allMatch(ClusterCommand::isUndoable);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return name + " (" + commands.size() + " commands)";
        }
    }

    // ==================== Command Invoker ====================

    /**
     * Command invoker with history and undo support.
     */
    public static class CommandInvoker {
        private final Deque<ClusterCommand> history;
        private final Deque<ClusterCommand> redoStack;
        private final List<CommandListener> listeners;
        private final int maxHistorySize;

        public CommandInvoker() {
            this(100);
        }

        public CommandInvoker(int maxHistorySize) {
            this.history = new ArrayDeque<>();
            this.redoStack = new ArrayDeque<>();
            this.listeners = new ArrayList<>();
            this.maxHistorySize = maxHistorySize;
        }

        /**
         * Executes a command and adds to history.
         */
        public CommandResult execute(ClusterCommand command) {
            log.info("Executing command", "name", command.getName());

            CommandResult result = command.execute();

            if (result.success() && command.isUndoable()) {
                history.push(command);
                redoStack.clear(); // Clear redo stack on new command

                // Limit history size
                while (history.size() > maxHistorySize) {
                    history.removeLast();
                }
            }

            notifyListeners(command, result);
            return result;
        }

        /**
         * Executes a command asynchronously.
         */
        public CompletableFuture<CommandResult> executeAsync(ClusterCommand command) {
            return CompletableFuture.supplyAsync(() -> execute(command));
        }

        /**
         * Undoes the last command.
         */
        public boolean undo() {
            if (history.isEmpty()) {
                return false;
            }

            ClusterCommand command = history.pop();
            log.info("Undoing command", "name", command.getName());

            if (command.undo()) {
                redoStack.push(command);
                return true;
            }
            return false;
        }

        /**
         * Redoes the last undone command.
         */
        public boolean redo() {
            if (redoStack.isEmpty()) {
                return false;
            }

            ClusterCommand command = redoStack.pop();
            log.info("Redoing command", "name", command.getName());

            CommandResult result = command.execute();
            if (result.success()) {
                history.push(command);
                return true;
            }
            return false;
        }

        /**
         * Gets command history.
         */
        public List<String> getHistory() {
            return history.stream()
                    .map(ClusterCommand::getDescription)
                    .toList();
        }

        /**
         * Checks if undo is available.
         */
        public boolean canUndo() {
            return !history.isEmpty();
        }

        /**
         * Checks if redo is available.
         */
        public boolean canRedo() {
            return !redoStack.isEmpty();
        }

        /**
         * Clears command history.
         */
        public void clearHistory() {
            history.clear();
            redoStack.clear();
        }

        /**
         * Adds a command listener.
         */
        public void addListener(CommandListener listener) {
            listeners.add(listener);
        }

        private void notifyListeners(ClusterCommand command, CommandResult result) {
            for (CommandListener listener : listeners) {
                try {
                    listener.onCommandExecuted(command, result);
                } catch (Exception e) {
                    log.error("Command listener error", e);
                }
            }
        }

        /**
         * Command listener interface.
         */
        public interface CommandListener {
            void onCommandExecuted(ClusterCommand command, CommandResult result);
        }
    }

    // ==================== Memento Pattern: State Snapshots ====================

    /**
     * Cluster state memento for snapshots.
     * Memento Pattern - Captures cluster state for restoration.
     */
    public record ClusterMemento(
            String clusterId,
            Set<String> nodeIds,
            String leaderId,
            Instant capturedAt
    ) {
        /**
         * Creates a memento from current cluster state.
         */
        public static ClusterMemento capture(ClusterManager cluster) {
            return new ClusterMemento(
                    cluster.getClusterId(),
                    new HashSet<>(cluster.getAllNodes().stream()
                            .map(n -> n.getNode().getNodeId())
                            .toList()),
                    cluster.getLeaderId().orElse(null),
                    Instant.now()
            );
        }
    }

    /**
     * Caretaker that manages mementos.
     */
    public static class ClusterStateCaretaker {
        private final Deque<ClusterMemento> snapshots;
        private final int maxSnapshots;

        public ClusterStateCaretaker() {
            this(10);
        }

        public ClusterStateCaretaker(int maxSnapshots) {
            this.snapshots = new ArrayDeque<>();
            this.maxSnapshots = maxSnapshots;
        }

        /**
         * Saves a snapshot.
         */
        public void saveSnapshot(ClusterMemento memento) {
            snapshots.push(memento);
            while (snapshots.size() > maxSnapshots) {
                snapshots.removeLast();
            }
            log.debug("Cluster snapshot saved", "nodeCount", memento.nodeIds().size());
        }

        /**
         * Restores the most recent snapshot.
         */
        public Optional<ClusterMemento> restoreSnapshot() {
            if (snapshots.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(snapshots.pop());
        }

        /**
         * Gets all snapshots.
         */
        public List<ClusterMemento> getSnapshots() {
            return new ArrayList<>(snapshots);
        }

        /**
         * Checks if snapshots are available.
         */
        public boolean hasSnapshots() {
            return !snapshots.isEmpty();
        }
    }
}
