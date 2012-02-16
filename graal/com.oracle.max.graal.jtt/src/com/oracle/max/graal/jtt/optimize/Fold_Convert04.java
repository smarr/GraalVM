/*
 * Copyright (c) 2009, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.max.graal.jtt.optimize;

import org.junit.*;

/*
 * Tests constant folding of float conversions
 */
public class Fold_Convert04 {

    public static double test(double arg) {
        if (arg == 0) {
            return l2d();
        }
        if (arg == 1) {
            return f2d();
        }
        return 0;
    }

    public static double l2d() {
        long x = 1024;
        return x;
    }

    public static double f2d() {
        float x = -1.25f;
        return x;
    }

    @Test
    public void run0() throws Throwable {
        Assert.assertEquals(1024d, test(0), 0);
    }

    @Test
    public void run1() throws Throwable {
        Assert.assertEquals(-1.25d, test(1), 0);
    }

}
