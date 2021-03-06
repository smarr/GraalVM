/*
 * Copyright (c) 2012, 2015, Oracle and/or its affiliates. All rights reserved.
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

import java.util.*;

import jdk.internal.jvmci.code.*;
import jdk.internal.jvmci.meta.*;

import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodeinfo.*;

@NodeInfo
public abstract class LoweredCallTargetNode extends CallTargetNode {

    public static final NodeClass<LoweredCallTargetNode> TYPE = NodeClass.create(LoweredCallTargetNode.class);
    protected final Stamp returnStamp;
    protected final JavaType[] signature;
    protected final CallingConvention.Type callType;

    protected LoweredCallTargetNode(NodeClass<? extends LoweredCallTargetNode> c, List<ValueNode> arguments, Stamp returnStamp, JavaType[] signature, ResolvedJavaMethod target,
                    CallingConvention.Type callType, InvokeKind invokeKind) {
        super(c, arguments, target, invokeKind);
        this.returnStamp = returnStamp;
        this.signature = signature;
        this.callType = callType;
    }

    @Override
    public Stamp returnStamp() {
        return returnStamp;
    }

    public JavaType[] signature() {
        return signature;
    }

    public CallingConvention.Type callType() {
        return callType;
    }
}
