/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.replacements.test;

import java.lang.reflect.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.runtime.*;
import com.oracle.graal.compiler.test.*;
import com.oracle.graal.test.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.replacements.*;
import com.oracle.graal.replacements.Snippet.*;
import com.oracle.graal.word.*;

/**
 * Tests for the {@link Word} type.
 */
public class WordTest extends GraalCompilerTest implements Snippets {

    private final ReplacementsImpl installer;

    public WordTest() {
        TargetDescription target = Graal.getRequiredCapability(CodeCacheProvider.class).getTarget();
        installer = new ReplacementsImpl(getMetaAccess(), getCodeCache(), new Assumptions(false), target);
    }

    private static final ThreadLocal<SnippetInliningPolicy> inliningPolicy = new ThreadLocal<>();

    @Override
    protected StructuredGraph parse(Method m) {
        ResolvedJavaMethod resolvedMethod = getMetaAccess().lookupJavaMethod(m);
        return installer.makeGraph(resolvedMethod, null, inliningPolicy.get(), false);
    }

    @LongTest
    public void construction() {
        long[] words = new long[]{Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, 1L, Long.MAX_VALUE - 1, Long.MAX_VALUE, Integer.MAX_VALUE - 1L, Integer.MAX_VALUE, Integer.MAX_VALUE + 1L,
                        Integer.MIN_VALUE - 1L, Integer.MIN_VALUE, Integer.MIN_VALUE + 1L};
        for (long word : words) {
            test("unsignedLong", word);
            test("unsignedInt", (int) word);
            test("signedLong", word);
            test("signedInt", (int) word);
        }
    }

    @LongTest
    public void testArithmetic() {
        long[] words = new long[]{Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, 1L, Long.MAX_VALUE - 1, Long.MAX_VALUE, Integer.MAX_VALUE - 1L, Integer.MAX_VALUE, Integer.MAX_VALUE + 1L,
                        Integer.MIN_VALUE - 1L, Integer.MIN_VALUE, Integer.MIN_VALUE + 1L};
        for (long word : words) {
            test("unsignedNot", word);
            test("signedNot", word);
            for (long addend : words) {
                test("unsignedPlusInt", word, (int) addend);
                test("unsignedMinusInt", word, (int) addend);
                test("unsignedPlusInt", word, -((int) addend));
                test("unsignedMinusInt", word, -((int) addend));
                test("unsignedPlusLong", word, addend);
                test("unsignedMinusLong", word, addend);
                test("unsignedPlusLong", word, -addend);
                test("unsignedMinusLong", word, -addend);
                test("signedPlusInt", word, (int) addend);
                test("signedMinusInt", word, (int) addend);
                test("signedPlusInt", word, -((int) addend));
                test("signedMinusInt", word, -((int) addend));
                test("signedPlusLong", word, addend);
                test("signedMinusLong", word, addend);
                test("signedPlusLong", word, -addend);
                test("signedMinusLong", word, -addend);

                test("andInt", word, (int) addend);
                test("orInt", word, (int) addend);
                test("andInt", word, -((int) addend));
                test("orInt", word, -((int) addend));
                test("andLong", word, addend);
                test("orLong", word, addend);
                test("andLong", word, -addend);
                test("orLong", word, -addend);
            }
        }
    }

    @LongTest
    public void testCompare() {
        long[] words = new long[]{Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, 1L, Long.MAX_VALUE - 1, Long.MAX_VALUE};
        for (long word1 : words) {
            for (long word2 : words) {
                for (String method : new String[]{"aboveOrEqual", "above", "belowOrEqual", "below"}) {
                    test(method, word1, word2);
                    test(method, word2, word1);
                }
            }
        }
    }

    @Snippet
    public static long unsignedLong(long word) {
        return Word.unsigned(word).rawValue();
    }

    @Snippet
    public static long unsignedInt(int word) {
        return Word.unsigned(word).rawValue();
    }

    @Snippet
    public static long signedLong(long word) {
        return Word.signed(word).rawValue();
    }

    @Snippet
    public static long signedInt(int word) {
        return Word.signed(word).rawValue();
    }

    @Snippet
    public static long unsignedPlusInt(long word, int addend) {
        return Word.unsigned(word).add(addend).rawValue();
    }

    @Snippet
    public static long unsignedMinusInt(long word, int addend) {
        return Word.unsigned(word).subtract(addend).rawValue();
    }

    @Snippet
    public static long unsignedPlusLong(long word, long addend) {
        return Word.unsigned(word).add(Word.unsigned(addend)).rawValue();
    }

    @Snippet
    public static long unsignedMinusLong(long word, long addend) {
        return Word.unsigned(word).subtract(Word.unsigned(addend)).rawValue();
    }

    @Snippet
    public static long signedPlusInt(long word, int addend) {
        return Word.signed(word).add(addend).rawValue();
    }

    @Snippet
    public static long signedMinusInt(long word, int addend) {
        return Word.signed(word).subtract(addend).rawValue();
    }

    @Snippet
    public static long signedPlusLong(long word, long addend) {
        return Word.signed(word).add(Word.signed(addend)).rawValue();
    }

    @Snippet
    public static long signedMinusLong(long word, long addend) {
        return Word.signed(word).subtract(Word.signed(addend)).rawValue();
    }

    @Snippet
    public static long signedNot(long word) {
        return Word.signed(word).not().rawValue();
    }

    @Snippet
    public static long unsignedNot(long word) {
        return Word.unsigned(word).not().rawValue();
    }

    @Snippet
    public static boolean aboveOrEqual(long word1, long word2) {
        return Word.unsigned(word1).aboveOrEqual(Word.unsigned(word2));
    }

    @Snippet
    public static boolean above(long word1, long word2) {
        return Word.unsigned(word1).aboveThan(Word.unsigned(word2));
    }

    @Snippet
    public static boolean belowOrEqual(long word1, long word2) {
        return Word.unsigned(word1).belowOrEqual(Word.unsigned(word2));
    }

    @Snippet
    public static boolean below(long word1, long word2) {
        return Word.unsigned(word1).belowThan(Word.unsigned(word2));
    }

    @Snippet
    public static long andInt(long word, int addend) {
        return Word.unsigned(word).and(addend).rawValue();
    }

    @Snippet
    public static long orInt(long word, int addend) {
        return Word.unsigned(word).or(addend).rawValue();
    }

    @Snippet
    public static long andLong(long word, long addend) {
        return Word.unsigned(word).and(Word.unsigned(addend)).rawValue();
    }

    @Snippet
    public static long orLong(long word, long addend) {
        return Word.unsigned(word).or(Word.unsigned(addend)).rawValue();
    }
}
