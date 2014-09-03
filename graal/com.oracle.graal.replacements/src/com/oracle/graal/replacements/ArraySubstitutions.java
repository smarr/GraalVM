/*
 * Copyright (c) 2013, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.replacements;

import com.oracle.graal.api.replacements.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.*;

/**
 * Substitutions for {@link java.lang.reflect.Array} methods.
 */
@ClassSubstitution(java.lang.reflect.Array.class)
public class ArraySubstitutions {

    @MethodSubstitution
    public static Object newInstance(Class<?> componentType, int length) throws NegativeArraySizeException {
        // The error cases must be handled here since DynamicNewArrayNode can only deoptimize the
        // caller in response to exceptions.
        if (length < 0) {
            throw new NegativeArraySizeException();
        }
        if (componentType == void.class) {
            throw new IllegalArgumentException();
        }
        return DynamicNewArrayNode.newArray(GuardingPiNode.guardingNonNull(componentType), length);
    }

    @MethodSubstitution
    public static int getLength(Object array) {
        if (!array.getClass().isArray()) {
            throw new IllegalArgumentException("Argument is not an array");
        }
        return ArrayLengthNode.arrayLength(array);
    }

}