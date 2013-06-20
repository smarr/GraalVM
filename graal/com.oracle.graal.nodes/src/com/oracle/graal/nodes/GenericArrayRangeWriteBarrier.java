/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes;

import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.type.*;

public final class GenericArrayRangeWriteBarrier extends ArrayRangeWriteNode implements Node.IterableNodeType {

    @Input private ValueNode dstObject;
    @Input private ValueNode dstPos;
    @Input private ValueNode length;

    @Override
    public ValueNode getArray() {
        return dstObject;
    }

    @Override
    public ValueNode getIndex() {
        return dstPos;
    }

    @Override
    public ValueNode getLength() {
        return length;
    }

    @Override
    public boolean isObjectArray() {
        return true;
    }

    public GenericArrayRangeWriteBarrier(ValueNode dstObject, ValueNode dstPos, ValueNode length) {
        super(StampFactory.forVoid());
        this.dstObject = dstObject;
        this.dstPos = dstPos;
        this.length = length;

    }

    @NodeIntrinsic
    public static native void insertWriteBarrier(Object dstObject, int dstPos, int length);
}
