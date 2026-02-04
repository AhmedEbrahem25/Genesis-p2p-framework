package com.genesis.p2p.util.common;

import com.genesis.p2p.testutil.base.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Bytes Utility Tests")
class BytesTest extends BaseUnitTest {

    @Test
    @DisplayName("Should return EMPTY constant for null input")
    void testEmptyConstant() {
        assertNotNull(Bytes.EMPTY);
        assertEquals(0, Bytes.EMPTY.length);
    }

    @Test
    @DisplayName("Should concatenate byte arrays")
    void testConcatenate() {
        byte[] a = {0x01, 0x02};
        byte[] b = {0x03, 0x04};
        byte[] result = Bytes.concat(a, b);
        assertNotNull(result);
        assertEquals(4, result.length);
        assertEquals(0x01, result[0]);
        assertEquals(0x04, result[3]);
    }

    @Test
    @DisplayName("Should handle null arrays in concat")
    void testConcatNullArrays() {
        byte[] a = {0x01, 0x02};
        byte[] result = Bytes.concat(a, null);
        assertNotNull(result);
        assertEquals(2, result.length);
    }

    @Test
    @DisplayName("Should handle empty concat")
    void testConcatEmpty() {
        byte[] result = Bytes.concat();
        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    @DisplayName("Should slice byte array")
    void testSlice() {
        byte[] bytes = {0x01, 0x02, 0x03, 0x04, 0x05};
        byte[] sliced = Bytes.slice(bytes, 1, 3);
        assertNotNull(sliced);
        assertEquals(3, sliced.length);
        assertEquals(0x02, sliced[0]);
        assertEquals(0x04, sliced[2]);
    }

    @Test
    @DisplayName("Should throw on null slice source")
    void testSliceNullSource() {
        assertThrows(IllegalArgumentException.class, () -> Bytes.slice(null, 0, 1));
    }

    @Test
    @DisplayName("Should compare byte arrays correctly")
    void testEquals() {
        byte[] a = {0x01, 0x02, 0x03};
        byte[] b = {0x01, 0x02, 0x03};
        byte[] c = {0x01, 0x02, 0x04};

        assertTrue(Arrays.equals(a, b));
        assertFalse(Arrays.equals(a, c));
    }

    @Test
    @DisplayName("Should concatenate multiple arrays")
    void testConcatMultiple() {
        byte[] a = {0x01};
        byte[] b = {0x02};
        byte[] c = {0x03};
        byte[] result = Bytes.concat(a, b, c);
        assertEquals(3, result.length);
        assertEquals(0x01, result[0]);
        assertEquals(0x02, result[1]);
        assertEquals(0x03, result[2]);
    }
}
