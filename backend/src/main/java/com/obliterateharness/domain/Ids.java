package com.obliterateharness.domain;

import java.security.SecureRandom;

/** IDs curtos e legíveis com prefixo por tipo (n_, e_, f_, nt_, rt_, ot_, p_). */
public final class Ids {
    private static final char[] ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {}

    public static String next(String prefix) {
        char[] out = new char[10];
        for (int i = 0; i < out.length; i++) out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        return prefix + "_" + new String(out);
    }
}
