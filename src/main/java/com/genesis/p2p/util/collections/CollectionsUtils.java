package com.genesis.p2p.util.collections;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Collections Utilities for common operations.
 *
 * Provides helper methods for working with collections:
 * - Safe null handling
 * - Empty checks
 * - Partition operations
 * - Batch processing
 * - Set operations
 * - Map transformations
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public final class CollectionsUtils {

    private CollectionsUtils() {
        throw new AssertionError("Utility class - do not instantiate");
    }

    // ==================== Null-Safe Operations ====================

    /**
     * Returns empty list if null.
     */
    public static <T> List<T> emptyIfNull(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * Returns empty set if null.
     */
    public static <T> Set<T> emptyIfNull(Set<T> set) {
        return set == null ? Collections.emptySet() : set;
    }

    /**
     * Returns empty map if null.
     */
    public static <K, V> Map<K, V> emptyIfNull(Map<K, V> map) {
        return map == null ? Collections.emptyMap() : map;
    }

    // ==================== Empty Checks ====================

    /**
     * Checks if collection is null or empty.
     */
    public static boolean isEmpty(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }

    /**
     * Checks if map is null or empty.
     */
    public static boolean isEmpty(Map<?, ?> map) {
        return map == null || map.isEmpty();
    }

    /**
     * Checks if collection is not null and not empty.
     */
    public static boolean isNotEmpty(Collection<?> collection) {
        return !isEmpty(collection);
    }

    /**
     * Checks if map is not null and not empty.
     */
    public static boolean isNotEmpty(Map<?, ?> map) {
        return !isEmpty(map);
    }

    // ==================== Size Operations ====================

    /**
     * Gets size of collection (0 if null).
     */
    public static int size(Collection<?> collection) {
        return collection == null ? 0 : collection.size();
    }

    /**
     * Gets size of map (0 if null).
     */
    public static int size(Map<?, ?> map) {
        return map == null ? 0 : map.size();
    }

    // ==================== First/Last ====================

    /**
     * Gets first element from list (null if empty).
     */
    public static <T> T first(List<T> list) {
        return isEmpty(list) ? null : list.get(0);
    }

    /**
     * Gets last element from list (null if empty).
     */
    public static <T> T last(List<T> list) {
        return isEmpty(list) ? null : list.get(list.size() - 1);
    }

    /**
     * Gets first element from collection (null if empty).
     */
    public static <T> T first(Collection<T> collection) {
        if (isEmpty(collection)) {
            return null;
        }
        return collection.iterator().next();
    }

    // ==================== Partition ====================

    /**
     * Partitions list into batches of specified size.
     *
     * @param list the list to partition
     * @param batchSize the batch size
     * @return list of batches
     */
    public static <T> List<List<T>> partition(List<T> list, int batchSize) {
        if (isEmpty(list) || batchSize <= 0) {
            return Collections.emptyList();
        }

        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            int end = Math.min(i + batchSize, list.size());
            batches.add(new ArrayList<>(list.subList(i, end)));
        }
        return batches;
    }

    /**
     * Partitions collection by predicate.
     *
     * @param collection the collection
     * @param predicate the predicate
     * @return map with true/false keys
     */
    public static <T> Map<Boolean, List<T>> partitionBy(
            Collection<T> collection, Predicate<T> predicate) {
        if (isEmpty(collection)) {
            return Map.of(true, List.of(), false, List.of());
        }
        return collection.stream()
                .collect(Collectors.partitioningBy(predicate));
    }

    // ==================== Filter ====================

    /**
     * Filters collection by predicate.
     */
    public static <T> List<T> filter(Collection<T> collection, Predicate<T> predicate) {
        if (isEmpty(collection)) {
            return Collections.emptyList();
        }
        return collection.stream()
                .filter(predicate)
                .collect(Collectors.toList());
    }

    /**
     * Removes null elements from collection.
     */
    public static <T> List<T> removeNulls(Collection<T> collection) {
        return filter(collection, Objects::nonNull);
    }

    // ==================== Set Operations ====================

    /**
     * Union of two sets.
     */
    public static <T> Set<T> union(Set<T> set1, Set<T> set2) {
        Set<T> result = new HashSet<>(emptyIfNull(set1));
        result.addAll(emptyIfNull(set2));
        return result;
    }

    /**
     * Intersection of two sets.
     */
    public static <T> Set<T> intersection(Set<T> set1, Set<T> set2) {
        if (isEmpty(set1) || isEmpty(set2)) {
            return Collections.emptySet();
        }
        Set<T> result = new HashSet<>(set1);
        result.retainAll(set2);
        return result;
    }

    /**
     * Difference of two sets (set1 - set2).
     */
    public static <T> Set<T> difference(Set<T> set1, Set<T> set2) {
        if (isEmpty(set1)) {
            return Collections.emptySet();
        }
        Set<T> result = new HashSet<>(set1);
        if (isNotEmpty(set2)) {
            result.removeAll(set2);
        }
        return result;
    }

    /**
     * Symmetric difference (elements in either set but not both).
     */
    public static <T> Set<T> symmetricDifference(Set<T> set1, Set<T> set2) {
        Set<T> diff1 = difference(set1, set2);
        Set<T> diff2 = difference(set2, set1);
        return union(diff1, diff2);
    }

    // ==================== Map Operations ====================

    /**
     * Inverts map (values become keys, keys become values).
     * Note: Values must be unique.
     */
    public static <K, V> Map<V, K> invert(Map<K, V> map) {
        if (isEmpty(map)) {
            return Collections.emptyMap();
        }
        return map.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
    }

    /**
     * Filters map by key predicate.
     */
    public static <K, V> Map<K, V> filterKeys(Map<K, V> map, Predicate<K> predicate) {
        if (isEmpty(map)) {
            return Collections.emptyMap();
        }
        return map.entrySet().stream()
                .filter(e -> predicate.test(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Filters map by value predicate.
     */
    public static <K, V> Map<K, V> filterValues(Map<K, V> map, Predicate<V> predicate) {
        if (isEmpty(map)) {
            return Collections.emptyMap();
        }
        return map.entrySet().stream()
                .filter(e -> predicate.test(e.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // ==================== Conversion ====================

    /**
     * Converts collection to list.
     */
    public static <T> List<T> toList(Collection<T> collection) {
        if (isEmpty(collection)) {
            return Collections.emptyList();
        }
        return new ArrayList<>(collection);
    }

    /**
     * Converts collection to set.
     */
    public static <T> Set<T> toSet(Collection<T> collection) {
        if (isEmpty(collection)) {
            return Collections.emptySet();
        }
        return new HashSet<>(collection);
    }

    /**
     * Creates unmodifiable list from elements.
     */
    @SafeVarargs
    public static <T> List<T> listOf(T... elements) {
        if (elements == null || elements.length == 0) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(Arrays.asList(elements));
    }

    /**
     * Creates unmodifiable set from elements.
     */
    @SafeVarargs
    public static <T> Set<T> setOf(T... elements) {
        if (elements == null || elements.length == 0) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(elements)));
    }

    // ==================== Safe Get ====================

    /**
     * Gets element at index (null if out of bounds).
     */
    public static <T> T getOrNull(List<T> list, int index) {
        if (list == null || index < 0 || index >= list.size()) {
            return null;
        }
        return list.get(index);
    }

    /**
     * Gets value from map (default if not found).
     */
    public static <K, V> V getOrDefault(Map<K, V> map, K key, V defaultValue) {
        if (map == null) {
            return defaultValue;
        }
        return map.getOrDefault(key, defaultValue);
    }

    // ==================== Safe Put ====================

    /**
     * Puts value into map if key is absent.
     */
    public static <K, V> V putIfAbsent(Map<K, V> map, K key, V value) {
        if (map == null) {
            return null;
        }
        return map.putIfAbsent(key, value);
    }

    // ==================== Add All ====================

    /**
     * Adds all elements from source to target (null-safe).
     */
    public static <T> boolean addAll(Collection<T> target, Collection<T> source) {
        if (target == null || isEmpty(source)) {
            return false;
        }
        return target.addAll(source);
    }

    /**
     * Puts all entries from source to target (null-safe).
     */
    public static <K, V> void putAll(Map<K, V> target, Map<K, V> source) {
        if (target != null && isNotEmpty(source)) {
            target.putAll(source);
        }
    }

    // ==================== Frequency ====================

    /**
     * Counts occurrences of each element.
     */
    public static <T> Map<T, Long> frequency(Collection<T> collection) {
        if (isEmpty(collection)) {
            return Collections.emptyMap();
        }
        return collection.stream()
                .collect(Collectors.groupingBy(e -> e, Collectors.counting()));
    }

    // ==================== Find ====================

    /**
     * Finds first element matching predicate.
     */
    public static <T> Optional<T> find(Collection<T> collection, Predicate<T> predicate) {
        if (isEmpty(collection)) {
            return Optional.empty();
        }
        return collection.stream()
                .filter(predicate)
                .findFirst();
    }

    /**
     * Checks if any element matches predicate.
     */
    public static <T> boolean anyMatch(Collection<T> collection, Predicate<T> predicate) {
        if (isEmpty(collection)) {
            return false;
        }
        return collection.stream().anyMatch(predicate);
    }

    /**
     * Checks if all elements match predicate.
     */
    public static <T> boolean allMatch(Collection<T> collection, Predicate<T> predicate) {
        if (isEmpty(collection)) {
            return true; // vacuous truth
        }
        return collection.stream().allMatch(predicate);
    }

    // ==================== Join ====================

    /**
     * Joins collection elements into string.
     */
    public static String join(Collection<?> collection, String delimiter) {
        if (isEmpty(collection)) {
            return "";
        }
        return collection.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(delimiter));
    }

    /**
     * Joins collection with prefix and suffix.
     */
    public static String join(Collection<?> collection, String delimiter, 
                             String prefix, String suffix) {
        if (isEmpty(collection)) {
            return prefix + suffix;
        }
        return collection.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(delimiter, prefix, suffix));
    }

    // ==================== Reverse ====================

    /**
     * Reverses a list (creates new list).
     */
    public static <T> List<T> reverse(List<T> list) {
        if (isEmpty(list)) {
            return Collections.emptyList();
        }
        List<T> reversed = new ArrayList<>(list);
        Collections.reverse(reversed);
        return reversed;
    }

    // ==================== Shuffle ====================

    /**
     * Shuffles a list (creates new list).
     */
    public static <T> List<T> shuffle(List<T> list) {
        if (isEmpty(list)) {
            return Collections.emptyList();
        }
        List<T> shuffled = new ArrayList<>(list);
        Collections.shuffle(shuffled);
        return shuffled;
    }
}

