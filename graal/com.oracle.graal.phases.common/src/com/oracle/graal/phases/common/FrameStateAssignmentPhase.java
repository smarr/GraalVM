/*
 * Copyright (c) 2013, 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.phases.common;

import java.util.*;

import jdk.internal.jvmci.common.*;

import com.oracle.graal.graph.*;
import com.oracle.graal.graph.iterators.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.StructuredGraph.GuardsStage;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.graph.*;
import com.oracle.graal.phases.graph.ReentrantNodeIterator.NodeIteratorClosure;

/**
 * This phase transfers {@link FrameState} nodes from {@link StateSplit} nodes to
 * {@link DeoptimizingNode DeoptimizingNodes}.
 *
 * This allow to enter the {@link GuardsStage#AFTER_FSA AFTER_FSA} stage of the graph where no new
 * node that may cause deoptimization can be introduced anymore.
 * <p>
 * This Phase processes the graph in post order, assigning the {@link FrameState} from the last
 * {@link StateSplit} node to {@link DeoptimizingNode DeoptimizingNodes}.
 */
public class FrameStateAssignmentPhase extends Phase {

    private static class FrameStateAssignmentClosure extends NodeIteratorClosure<FrameState> {

        @Override
        protected FrameState processNode(FixedNode node, FrameState previousState) {
            FrameState currentState = previousState;
            if (node instanceof DeoptimizingNode.DeoptBefore) {
                DeoptimizingNode.DeoptBefore deopt = (DeoptimizingNode.DeoptBefore) node;
                if (deopt.canDeoptimize() && deopt.stateBefore() == null) {
                    JVMCIError.guarantee(currentState != null, "no FrameState at DeoptimizingNode %s", deopt);
                    deopt.setStateBefore(currentState);
                }
            }

            if (node instanceof StateSplit) {
                StateSplit stateSplit = (StateSplit) node;
                FrameState stateAfter = stateSplit.stateAfter();
                if (stateAfter != null) {
                    currentState = stateAfter;
                    stateSplit.setStateAfter(null);
                }
            }

            if (node instanceof DeoptimizingNode.DeoptDuring) {
                DeoptimizingNode.DeoptDuring deopt = (DeoptimizingNode.DeoptDuring) node;
                if (deopt.canDeoptimize()) {
                    JVMCIError.guarantee(currentState != null, "no FrameState at DeoptimizingNode %s", deopt);
                    deopt.computeStateDuring(currentState);
                }
            }

            if (node instanceof DeoptimizingNode.DeoptAfter) {
                DeoptimizingNode.DeoptAfter deopt = (DeoptimizingNode.DeoptAfter) node;
                if (deopt.canDeoptimize() && deopt.stateAfter() == null) {
                    JVMCIError.guarantee(currentState != null, "no FrameState at DeoptimizingNode %s", deopt);
                    deopt.setStateAfter(currentState);
                }
            }

            return currentState;
        }

        @Override
        protected FrameState merge(AbstractMergeNode merge, List<FrameState> states) {
            FrameState singleFrameState = singleFrameState(states);
            return singleFrameState == null ? merge.stateAfter() : singleFrameState;
        }

        @Override
        protected FrameState afterSplit(AbstractBeginNode node, FrameState oldState) {
            return oldState;
        }

        @Override
        protected Map<LoopExitNode, FrameState> processLoop(LoopBeginNode loop, FrameState initialState) {
            return ReentrantNodeIterator.processLoop(this, loop, initialState).exitStates;
        }
    }

    @Override
    protected void run(StructuredGraph graph) {
        assert !graph.getGuardsStage().allowsFloatingGuards() && checkFixedDeopts(graph);
        if (graph.getGuardsStage().areFrameStatesAtSideEffects()) {
            ReentrantNodeIterator.apply(new FrameStateAssignmentClosure(), graph.start(), null);
            graph.setGuardsStage(GuardsStage.AFTER_FSA);
            graph.getNodes(FrameState.TYPE).filter(state -> state.hasNoUsages()).forEach(GraphUtil::killWithUnusedFloatingInputs);
        }
    }

    private static boolean checkFixedDeopts(StructuredGraph graph) {
        NodePredicate isFloatingNode = GraphUtil.isFloatingNode();
        for (Node n : graph.getNodes().filterInterface(DeoptimizingNode.class)) {
            if (((DeoptimizingNode) n).canDeoptimize() && isFloatingNode.apply(n)) {
                return false;
            }
        }
        return true;
    }

    private static FrameState singleFrameState(List<FrameState> states) {
        FrameState singleState = states.get(0);
        for (int i = 1; i < states.size(); ++i) {
            if (states.get(i) != singleState) {
                return null;
            }
        }
        return singleState;
    }
}
