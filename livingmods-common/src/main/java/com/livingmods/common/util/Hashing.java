package com.livingmods.common.util;

import java.nio.charset.StandardCharsets;

public final class Hashing {
    private Hashing() {}

    public static long mix(long a, long b) {
        long x = a ^ (b + 0x9E3779B97F4A7C15L + (a << 6) + (a >> 2));
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    public static long hashString(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        long h = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        return h;
    }

    public static long stateHash(Object... parts) {
        long h = 0x6a09e667f3bcc909L;
        for (Object p : parts) {
            long v = p == null ? 0L : (long) p.hashCode();
            h = mix(h, v);
        }
        return h;
    }
}
