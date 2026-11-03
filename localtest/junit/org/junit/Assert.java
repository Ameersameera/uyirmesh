package org.junit;

import java.util.Arrays;
import java.util.Objects;

/** Local stand-in for the subset of JUnit 4's Assert used by the core tests. */
public class Assert {
    public static void fail(String m) { throw new AssertionError(m); }
    public static void fail() { throw new AssertionError(); }
    public static void assertTrue(boolean c) { if (!c) fail("expected true"); }
    public static void assertTrue(String m, boolean c) { if (!c) fail(m); }
    public static void assertFalse(boolean c) { if (c) fail("expected false"); }
    public static void assertFalse(String m, boolean c) { if (c) fail(m); }
    public static void assertEquals(Object e, Object a) { if (!Objects.equals(e, a)) fail("expected <" + e + "> but was <" + a + ">"); }
    public static void assertEquals(String m, Object e, Object a) { if (!Objects.equals(e, a)) fail(m + ": expected <" + e + "> but was <" + a + ">"); }
    public static void assertEquals(long e, long a) { if (e != a) fail("expected <" + e + "> but was <" + a + ">"); }
    public static void assertEquals(String m, long e, long a) { if (e != a) fail(m + ": expected <" + e + "> but was <" + a + ">"); }
    public static void assertEquals(double e, double a, double d) { if (Math.abs(e - a) > d) fail("expected <" + e + "> but was <" + a + ">"); }
    public static void assertEquals(String m, double e, double a, double d) { if (Math.abs(e - a) > d) fail(m + ": expected <" + e + "> but was <" + a + ">"); }
    public static void assertNotEquals(Object u, Object a) { if (Objects.equals(u, a)) fail("values should differ: " + a); }
    public static void assertNotNull(Object o) { if (o == null) fail("expected not null"); }
    public static void assertNotNull(String m, Object o) { if (o == null) fail(m); }
    public static void assertNull(Object o) { if (o != null) fail("expected null but was " + o); }
    public static void assertNull(String m, Object o) { if (o != null) fail(m + ": expected null but was " + o); }
    public static void assertArrayEquals(byte[] e, byte[] a) { if (!Arrays.equals(e, a)) fail("arrays differ"); }
    public static void assertArrayEquals(String m, byte[] e, byte[] a) { if (!Arrays.equals(e, a)) fail(m + ": arrays differ"); }
}
