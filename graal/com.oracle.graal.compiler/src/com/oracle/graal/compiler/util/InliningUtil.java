/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.compiler.util;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

import com.oracle.graal.compiler.*;
import com.oracle.graal.compiler.loop.*;
import com.oracle.graal.compiler.phases.*;
import com.oracle.graal.compiler.phases.CanonicalizerPhase.IsImmutablePredicate;
import com.oracle.graal.cri.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.Node.*;
import com.oracle.graal.lir.cfg.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.PhiNode.PhiType;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.java.MethodCallTargetNode.InvokeKind;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.max.cri.ci.*;
import com.oracle.max.cri.ri.*;
import com.oracle.max.cri.ri.RiTypeProfile.ProfiledType;

public class InliningUtil {

    public interface InliningCallback {
        StructuredGraph buildGraph(RiResolvedMethod method);
        double inliningWeight(RiResolvedMethod caller, RiResolvedMethod method, Invoke invoke);
        void recordMethodContentsAssumption(RiResolvedMethod method);
        void recordConcreteMethodAssumption(RiResolvedMethod method, RiResolvedType context, RiResolvedMethod impl);
    }

    public static String methodName(RiResolvedMethod method, Invoke invoke) {
        if (!Debug.isLogEnabled()) {
            return null;
        } else if (invoke != null && invoke.stateAfter() != null) {
            return methodName(invoke.stateAfter(), invoke.bci()) + ": " + CiUtil.format("%H.%n(%p):%r", method) + " (" + method.codeSize() + " bytes)";
        } else {
            return CiUtil.format("%H.%n(%p):%r", method) + " (" + method.codeSize() + " bytes)";
        }
    }

    public static String methodName(InlineInfo info) {
        if (!Debug.isLogEnabled()) {
            return null;
        } else if (info.invoke != null && info.invoke.stateAfter() != null) {
            return methodName(info.invoke.stateAfter(), info.invoke.bci()) + ": " + info.toString();
        } else {
            return info.toString();
        }
    }

    private static String methodName(FrameState frameState, int bci) {
        StringBuilder sb = new StringBuilder();
        if (frameState.outerFrameState() != null) {
            sb.append(methodName(frameState.outerFrameState(), frameState.outerFrameState().bci));
            sb.append("->");
        }
        sb.append(CiUtil.format("%h.%n", frameState.method()));
        sb.append("@").append(bci);
        return sb.toString();
    }

    /**
     * Represents an opportunity for inlining at the given invoke, with the given weight and level.
     * The weight is the amortized weight of the additional code - so smaller is better.
     * The level is the number of nested inlinings that lead to this invoke.
     */
    public abstract static class InlineInfo implements Comparable<InlineInfo> {
        public final Invoke invoke;
        public final double weight;
        public final int level;

        public InlineInfo(Invoke invoke, double weight, int level) {
            this.invoke = invoke;
            this.weight = weight;
            this.level = level;
        }

        public abstract int compiledCodeSize();

        @Override
        public int compareTo(InlineInfo o) {
            return (weight < o.weight) ? -1 : (weight > o.weight) ? 1 : 0;
        }

        protected static StructuredGraph getGraph(final RiResolvedMethod concrete, final InliningCallback callback) {
            return Debug.scope("Inlining", concrete, new Callable<StructuredGraph>() {
                @Override
                public StructuredGraph call() throws Exception {
                    return callback.buildGraph(concrete);
                }
            });
        }

        public abstract boolean canDeopt();

        /**
         * Performs the inlining described by this object and returns the node that represents the return value of the
         * inlined method (or null for void methods and methods that have no non-exceptional exit).
         *
         * @param graph
         * @param runtime
         * @param callback
         */
        public abstract void inline(StructuredGraph graph, GraalRuntime runtime, InliningCallback callback);
    }

    /**
     * Represents an inlining opportunity where the compiler can statically determine a monomorphic target method and
     * therefore is able to determine the called method exactly.
     */
    private static class ExactInlineInfo extends InlineInfo {
        public final RiResolvedMethod concrete;

        public ExactInlineInfo(Invoke invoke, double weight, int level, RiResolvedMethod concrete) {
            super(invoke, weight, level);
            this.concrete = concrete;
        }

        @Override
        public void inline(StructuredGraph compilerGraph, GraalRuntime runtime, final InliningCallback callback) {
            StructuredGraph graph = getGraph(concrete, callback);
            assert !IntrinsificationPhase.canIntrinsify(invoke, concrete, runtime);
            callback.recordMethodContentsAssumption(concrete);
            InliningUtil.inline(invoke, graph, true);
        }

        @Override
        public int compiledCodeSize() {
            return concrete.compiledCodeSize();
        }

        @Override
        public String toString() {
            return "exact " + CiUtil.format("%H.%n(%p):%r", concrete);
        }

        @Override
        public boolean canDeopt() {
            return false;
        }
    }

    /**
     * Represents an inlining opportunity for which profiling information suggests a monomorphic receiver, but for which
     * the receiver type cannot be proven. A type check guard will be generated if this inlining is performed.
     */
    private static class TypeGuardInlineInfo extends InlineInfo {
        public final RiResolvedMethod concrete;
        public final RiResolvedType type;

        public TypeGuardInlineInfo(Invoke invoke, double weight, int level, RiResolvedMethod concrete, RiResolvedType type) {
            super(invoke, weight, level);
            this.concrete = concrete;
            this.type = type;
        }

        @Override
        public int compiledCodeSize() {
            return concrete.compiledCodeSize();
        }

        @Override
        public void inline(StructuredGraph graph, GraalRuntime runtime, InliningCallback callback) {
            // receiver null check must be before the type check
            InliningUtil.receiverNullCheck(invoke);
            ValueNode receiver = invoke.callTarget().receiver();
            ReadHubNode objectClass = graph.add(new ReadHubNode(receiver));
            IsTypeNode isTypeNode = graph.unique(new IsTypeNode(objectClass, type));
            FixedGuardNode guard = graph.add(new FixedGuardNode(isTypeNode, RiDeoptReason.TypeCheckedInliningViolated, RiDeoptAction.InvalidateReprofile, invoke.leafGraphId()));
            AnchorNode anchor = graph.add(new AnchorNode());
            assert invoke.predecessor() != null;

            ValueNode anchoredReceiver = createAnchoredReceiver(graph, anchor, type, receiver);
            invoke.callTarget().replaceFirstInput(receiver, anchoredReceiver);

            graph.addBeforeFixed(invoke.node(), objectClass);
            graph.addBeforeFixed(invoke.node(), guard);
            graph.addBeforeFixed(invoke.node(), anchor);

            StructuredGraph calleeGraph = getGraph(concrete, callback);
            assert !IntrinsificationPhase.canIntrinsify(invoke, concrete, runtime);
            callback.recordMethodContentsAssumption(concrete);
            InliningUtil.inline(invoke, calleeGraph, false);
        }

        @Override
        public String toString() {
            return "type-checked " + CiUtil.format("%H.%n(%p):%r", concrete);
        }

        @Override
        public boolean canDeopt() {
            return true;
        }
    }

    /**
     * Polymorphic inlining of m methods with n type checks (n >= m) in case that the profiling information suggests a reasonable
     * amounts of different receiver types and different methods. If an unknown type is encountered a deoptimization is triggered.
     */
    private static class MultiTypeGuardInlineInfo extends InlineInfo {
        public final List<RiResolvedMethod> concretes;
        public final ProfiledType[] ptypes;
        public final int[] typesToConcretes;
        public final double notRecordedTypeProbability;

        public MultiTypeGuardInlineInfo(Invoke invoke, double weight, int level, List<RiResolvedMethod> concretes, ProfiledType[] ptypes,
                        int[] typesToConcretes, double notRecordedTypeProbability) {
            super(invoke, weight, level);
            assert concretes.size() > 0 && concretes.size() <= ptypes.length : "must have at least one method but no more than types methods";
            assert ptypes.length == typesToConcretes.length : "array lengths must match";

            this.concretes = concretes;
            this.ptypes = ptypes;
            this.typesToConcretes = typesToConcretes;
            this.notRecordedTypeProbability = notRecordedTypeProbability;
        }

        @Override
        public int compiledCodeSize() {
            int result = 0;
            for (RiResolvedMethod m: concretes) {
                result += m.compiledCodeSize();
            }
            return result;
        }

        @Override
        public void inline(StructuredGraph graph, GraalRuntime runtime, InliningCallback callback) {
            int numberOfMethods = concretes.size();
            boolean hasReturnValue = invoke.node().kind() != CiKind.Void;

            // receiver null check must be the first node
            InliningUtil.receiverNullCheck(invoke);
            if (numberOfMethods > 1 || shouldFallbackToInvoke()) {
                inlineMultipleMethods(graph, runtime, callback, numberOfMethods, hasReturnValue);
            } else {
                inlineSingleMethod(graph, runtime, callback);
            }
        }

        private boolean shouldFallbackToInvoke() {
            return notRecordedTypeProbability > 0;
        }

        private void inlineMultipleMethods(StructuredGraph graph, GraalRuntime runtime, InliningCallback callback, int numberOfMethods, boolean hasReturnValue) {
            FixedNode continuation = invoke.next();

            // setup merge and phi nodes for results and exceptions
            MergeNode returnMerge = graph.add(new MergeNode());
            returnMerge.setProbability(invoke.probability());
            returnMerge.setStateAfter(invoke.stateAfter().duplicate(invoke.stateAfter().bci));

            PhiNode returnValuePhi = null;
            if (hasReturnValue) {
                returnValuePhi = graph.unique(new PhiNode(invoke.node().kind(), returnMerge, PhiType.Value));
            }

            MergeNode exceptionMerge = null;
            PhiNode exceptionObjectPhi = null;
            if (invoke instanceof InvokeWithExceptionNode) {
                InvokeWithExceptionNode invokeWithException = (InvokeWithExceptionNode) invoke;
                DispatchBeginNode exceptionEdge = invokeWithException.exceptionEdge();
                ExceptionObjectNode exceptionObject = (ExceptionObjectNode) exceptionEdge.next();

                exceptionMerge = graph.add(new MergeNode());
                exceptionMerge.setProbability(exceptionEdge.probability());

                FixedNode exceptionSux = exceptionObject.next();
                graph.addBeforeFixed(exceptionSux, exceptionMerge);
                exceptionObjectPhi = graph.unique(new PhiNode(CiKind.Object, exceptionMerge, PhiType.Value));
                exceptionMerge.setStateAfter(exceptionEdge.stateAfter().duplicateModified(invoke.stateAfter().bci, true, CiKind.Void, exceptionObjectPhi));
            }

            // create one separate block for each invoked method
            BeginNode[] calleeEntryNodes = new BeginNode[numberOfMethods];
            for (int i = 0; i < numberOfMethods; i++) {
                int predecessors = 0;
                double probability = 0;
                for (int j = 0; j < typesToConcretes.length; j++) {
                    if (typesToConcretes[j] == i) {
                        predecessors++;
                        probability += ptypes[j].probability;
                    }
                }

                calleeEntryNodes[i] = createInvocationBlock(graph, invoke, returnMerge, returnValuePhi, exceptionMerge, exceptionObjectPhi, predecessors, invoke.probability() * probability, true);
            }

            // create the successor for an unknown type
            FixedNode unknownTypeNode;
            if (shouldFallbackToInvoke()) {
                unknownTypeNode = createInvocationBlock(graph, invoke, returnMerge, returnValuePhi, exceptionMerge, exceptionObjectPhi, 1, notRecordedTypeProbability, false);
            } else {
                unknownTypeNode = graph.add(new DeoptimizeNode(RiDeoptAction.InvalidateReprofile, RiDeoptReason.TypeCheckedInliningViolated, invoke.leafGraphId()));
            }

            // replace the invoke exception edge
            if (invoke instanceof InvokeWithExceptionNode) {
                InvokeWithExceptionNode invokeWithExceptionNode = (InvokeWithExceptionNode) invoke;
                BeginNode exceptionEdge = invokeWithExceptionNode.exceptionEdge();
                ExceptionObjectNode exceptionObject = (ExceptionObjectNode) exceptionEdge.next();
                exceptionObject.replaceAtUsages(exceptionObjectPhi);
                exceptionObject.setNext(null);
                GraphUtil.killCFG(invokeWithExceptionNode.exceptionEdge());
            }

            // replace the invoke with a cascade of if nodes
            ReadHubNode objectClassNode = graph.add(new ReadHubNode(invoke.callTarget().receiver()));
            graph.addBeforeFixed(invoke.node(), objectClassNode);
            FixedNode dispatchOnType = createDispatchOnType(graph, objectClassNode, calleeEntryNodes, unknownTypeNode);

            assert invoke.next() == continuation;
            invoke.setNext(null);
            returnMerge.setNext(continuation);
            invoke.node().replaceAtUsages(returnValuePhi);
            invoke.node().replaceAndDelete(dispatchOnType);

            // do the actual inlining for every invoke
            for (int i = 0; i < calleeEntryNodes.length; i++) {
                BeginNode node = calleeEntryNodes[i];
                Invoke invokeForInlining = (Invoke) node.next();

                RiResolvedType commonType = getLeastCommonType(i);
                ValueNode receiver = invokeForInlining.callTarget().receiver();
                ValueNode anchoredReceiver = createAnchoredReceiver(graph, node, commonType, receiver);
                invokeForInlining.callTarget().replaceFirstInput(receiver, anchoredReceiver);

                RiResolvedMethod concrete = concretes.get(i);
                StructuredGraph calleeGraph = getGraph(concrete, callback);
                callback.recordMethodContentsAssumption(concrete);
                assert !IntrinsificationPhase.canIntrinsify(invokeForInlining, concrete, runtime);
                InliningUtil.inline(invokeForInlining, calleeGraph, false);
            }
        }

        private RiResolvedType getLeastCommonType(int concreteMethodIndex) {
            RiResolvedType commonType = null;
            for (int i = 0; i < typesToConcretes.length; i++) {
                if (typesToConcretes[i] == concreteMethodIndex) {
                    if (commonType == null) {
                        commonType = ptypes[i].type;
                    } else {
                        commonType = commonType.leastCommonAncestor(ptypes[i].type);
                    }
                }
            }
            assert commonType != null;
            return commonType;
        }

        private void inlineSingleMethod(StructuredGraph graph, GraalRuntime runtime, InliningCallback callback) {
            assert concretes.size() == 1 && ptypes.length > 1 && !shouldFallbackToInvoke() && notRecordedTypeProbability == 0;

            MergeNode calleeEntryNode = graph.add(new MergeNode());
            calleeEntryNode.setProbability(invoke.probability());
            ReadHubNode objectClassNode = graph.add(new ReadHubNode(invoke.callTarget().receiver()));
            graph.addBeforeFixed(invoke.node(), objectClassNode);

            FixedNode unknownTypeNode = graph.add(new DeoptimizeNode(RiDeoptAction.InvalidateReprofile, RiDeoptReason.TypeCheckedInliningViolated, invoke.leafGraphId()));
            FixedNode dispatchOnType = createDispatchOnType(graph, objectClassNode, new BeginNode[] {calleeEntryNode}, unknownTypeNode);

            FixedWithNextNode pred = (FixedWithNextNode) invoke.node().predecessor();
            pred.setNext(dispatchOnType);
            calleeEntryNode.setNext(invoke.node());

            RiResolvedMethod concrete = concretes.get(0);
            StructuredGraph calleeGraph = getGraph(concrete, callback);
            assert !IntrinsificationPhase.canIntrinsify(invoke, concrete, runtime);
            callback.recordMethodContentsAssumption(concrete);
            InliningUtil.inline(invoke, calleeGraph, false);
        }

        private FixedNode createDispatchOnType(StructuredGraph graph, ReadHubNode objectClassNode, BeginNode[] calleeEntryNodes, FixedNode unknownTypeSux) {
            assert ptypes.length > 1;

            int lastIndex = ptypes.length - 1;
            double[] branchProbabilities = convertTypeToBranchProbabilities(ptypes, notRecordedTypeProbability);
            double nodeProbability = ptypes[lastIndex].probability;
            IfNode nextNode = createTypeCheck(graph, objectClassNode, ptypes[lastIndex].type, calleeEntryNodes[typesToConcretes[lastIndex]], unknownTypeSux, branchProbabilities[lastIndex], invoke.probability() * nodeProbability);
            for (int i = lastIndex - 1; i >= 0; i--) {
                nodeProbability += ptypes[i].probability;
                nextNode = createTypeCheck(graph, objectClassNode, ptypes[i].type, calleeEntryNodes[typesToConcretes[i]], nextNode, branchProbabilities[i], invoke.probability() * nodeProbability);
            }

            return nextNode;
        }

        private static IfNode createTypeCheck(StructuredGraph graph, ReadHubNode objectClassNode, RiResolvedType type, BeginNode tsux, FixedNode nextNode, double tsuxProbability, double probability) {
            IfNode result;
            IsTypeNode isTypeNode = graph.unique(new IsTypeNode(objectClassNode, type));
            if (tsux instanceof MergeNode) {
                EndNode endNode = graph.add(new EndNode());
                result = graph.add(new IfNode(isTypeNode, endNode, nextNode, tsuxProbability));
                ((MergeNode) tsux).addForwardEnd(endNode);
            } else {
                result = graph.add(new IfNode(isTypeNode, tsux, nextNode, tsuxProbability));
            }
            result.setProbability(probability);
            return result;
        }

        private static double[] convertTypeToBranchProbabilities(ProfiledType[] ptypes, double notRecordedTypeProbability) {
            double[] result = new double[ptypes.length];
            double total = notRecordedTypeProbability;
            for (int i = ptypes.length - 1; i >= 0; i--) {
                total += ptypes[i].probability;
                result[i] = ptypes[i].probability / total;
            }
            assert total > 0.99 && total < 1.01;
            return result;
        }

        private static BeginNode createInvocationBlock(StructuredGraph graph, Invoke invoke, MergeNode returnMerge, PhiNode returnValuePhi,
                        MergeNode exceptionMerge, PhiNode exceptionObjectPhi, int predecessors, double probability, boolean useForInlining) {
            Invoke duplicatedInvoke = duplicateInvokeForInlining(graph, invoke, exceptionMerge, exceptionObjectPhi, useForInlining, probability);
            BeginNode calleeEntryNode = graph.add(predecessors > 1 ? new MergeNode() : new BeginNode());
            calleeEntryNode.setNext(duplicatedInvoke.node());
            calleeEntryNode.setProbability(probability);

            EndNode endNode = graph.add(new EndNode());
            endNode.setProbability(probability);

            duplicatedInvoke.setNext(endNode);
            returnMerge.addForwardEnd(endNode);

            if (returnValuePhi != null) {
                returnValuePhi.addInput(duplicatedInvoke.node());
            }
            return calleeEntryNode;
        }

        private static Invoke duplicateInvokeForInlining(StructuredGraph graph, Invoke invoke, MergeNode exceptionMerge, PhiNode exceptionObjectPhi, boolean useForInlining, double probability) {
            Invoke result = (Invoke) invoke.node().copyWithInputs();
            Node callTarget = result.callTarget().copyWithInputs();
            result.node().replaceFirstInput(result.callTarget(), callTarget);
            result.setUseForInlining(useForInlining);
            result.setProbability(probability);

            CiKind kind = invoke.node().kind();
            if (!kind.isVoid()) {
                FrameState stateAfter = invoke.stateAfter();
                stateAfter = stateAfter.duplicate(stateAfter.bci);
                stateAfter.replaceFirstInput(invoke.node(), result.node());
                result.setStateAfter(stateAfter);
            }

            if (invoke instanceof InvokeWithExceptionNode) {
                assert exceptionMerge != null && exceptionObjectPhi != null;

                InvokeWithExceptionNode invokeWithException = (InvokeWithExceptionNode) invoke;
                BeginNode exceptionEdge = invokeWithException.exceptionEdge();
                ExceptionObjectNode exceptionObject = (ExceptionObjectNode) exceptionEdge.next();
                FrameState stateAfterException = exceptionObject.stateAfter();

                BeginNode newExceptionEdge = (BeginNode) exceptionEdge.copyWithInputs();
                ExceptionObjectNode newExceptionObject = (ExceptionObjectNode) exceptionObject.copyWithInputs();
                // set new state (pop old exception object, push new one)
                newExceptionObject.setStateAfter(stateAfterException.duplicateModified(stateAfterException.bci, stateAfterException.rethrowException(), CiKind.Object, newExceptionObject));
                newExceptionEdge.setNext(newExceptionObject);

                EndNode endNode = graph.add(new EndNode());
                newExceptionObject.setNext(endNode);
                exceptionMerge.addForwardEnd(endNode);
                exceptionObjectPhi.addInput(newExceptionObject);

                ((InvokeWithExceptionNode) result).setExceptionEdge(newExceptionEdge);
            }
            return result;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder(shouldFallbackToInvoke() ? "megamorphic" : "polymorphic");
            builder.append(String.format(", %d methods with %d type checks:", concretes.size(), ptypes.length));
            for (int i = 0; i < concretes.size(); i++) {
                builder.append(CiUtil.format("  %H.%n(%p):%r", concretes.get(i)));
            }
            return builder.toString();
        }

        @Override
        public boolean canDeopt() {
            return true;
        }
    }


    /**
     * Represents an inlining opportunity where the current class hierarchy leads to a monomorphic target method,
     * but for which an assumption has to be registered because of non-final classes.
     */
    private static class AssumptionInlineInfo extends ExactInlineInfo {
        public final RiResolvedType context;

        public AssumptionInlineInfo(Invoke invoke, double weight, int level, RiResolvedType context, RiResolvedMethod concrete) {
            super(invoke, weight, level, concrete);
            this.context = context;
        }

        @Override
        public void inline(StructuredGraph graph, GraalRuntime runtime, InliningCallback callback) {
            if (Debug.isLogEnabled()) {
                String targetName = CiUtil.format("%H.%n(%p):%r", invoke.callTarget().targetMethod());
                String concreteName = CiUtil.format("%H.%n(%p):%r", concrete);
                Debug.log("recording concrete method assumption: %s on receiver type %s -> %s", targetName, context, concreteName);
            }
            callback.recordConcreteMethodAssumption(invoke.callTarget().targetMethod(), context, concrete);

            super.inline(graph, runtime, callback);
        }

        @Override
        public String toString() {
            return "assumption " + CiUtil.format("%H.%n(%p):%r", concrete);
        }

        @Override
        public boolean canDeopt() {
            return true;
        }
    }

    /**
     * Determines if inlining is possible at the given invoke node.
     * @param invoke the invoke that should be inlined
     * @param level the number of nested inlinings that lead to this invoke, or 0 if the invoke was part of the initial graph
     * @param runtime a GraalRuntime instance used to determine of the invoke can be inlined and/or should be intrinsified
     * @param callback a callback that is used to determine the weight of a specific inlining
     * @return an instance of InlineInfo, or null if no inlining is possible at the given invoke
     */
    public static InlineInfo getInlineInfo(Invoke invoke, int level, GraalRuntime runtime, CiAssumptions assumptions, InliningCallback callback, OptimisticOptimizations optimisticOpts) {
        RiResolvedMethod parent = invoke.stateAfter().method();
        MethodCallTargetNode callTarget = invoke.callTarget();
        RiResolvedMethod targetMethod = callTarget.targetMethod();
        if (targetMethod == null) {
            return null;
        }
        if (!checkInvokeConditions(invoke)) {
            return null;
        }

        if (callTarget.invokeKind() == InvokeKind.Special || targetMethod.canBeStaticallyBound()) {
            if (checkTargetConditions(invoke, targetMethod, optimisticOpts)) {
                double weight = callback == null ? 0 : callback.inliningWeight(parent, targetMethod, invoke);
                return new ExactInlineInfo(invoke, weight, level, targetMethod);
            }
            return null;
        }
        if (callTarget.receiver().exactType() != null) {
            RiResolvedType exact = callTarget.receiver().exactType();
            assert exact.isSubtypeOf(targetMethod.holder()) : exact + " subtype of " + targetMethod.holder() + " for " + targetMethod;
            RiResolvedMethod resolved = exact.resolveMethodImpl(targetMethod);
            if (checkTargetConditions(invoke, resolved, optimisticOpts)) {
                double weight = callback == null ? 0 : callback.inliningWeight(parent, resolved, invoke);
                return new ExactInlineInfo(invoke, weight, level, resolved);
            }
            return null;
        }
        RiResolvedType holder = targetMethod.holder();

        if (callTarget.receiver().declaredType() != null) {
            RiResolvedType declared = callTarget.receiver().declaredType();
            // the invoke target might be more specific than the holder (happens after inlining: locals lose their declared type...)
            // TODO (lstadler) fix this
            if (declared != null && declared.isSubtypeOf(holder)) {
                holder = declared;
            }
        }
        // TODO (thomaswue) fix this
        if (assumptions != null) {
            RiResolvedMethod concrete = holder.uniqueConcreteMethod(targetMethod);
            if (concrete != null) {
                if (checkTargetConditions(invoke, concrete, optimisticOpts)) {
                    double weight = callback == null ? 0 : callback.inliningWeight(parent, concrete, invoke);
                    return new AssumptionInlineInfo(invoke, weight, level, holder, concrete);
                }
                return null;
            }
        }

        // type check based inlining
        return getTypeCheckedInlineInfo(invoke, level, callback, parent, targetMethod, optimisticOpts);
    }

    private static InlineInfo getTypeCheckedInlineInfo(Invoke invoke, int level, InliningCallback callback, RiResolvedMethod parent, RiResolvedMethod targetMethod, OptimisticOptimizations optimisticOpts) {
        RiProfilingInfo profilingInfo = parent.profilingInfo();
        RiTypeProfile typeProfile = profilingInfo.getTypeProfile(invoke.bci());
        if (typeProfile != null) {
            ProfiledType[] ptypes = typeProfile.getTypes();

            if (ptypes != null && ptypes.length > 0) {
                double notRecordedTypeProbability = typeProfile.getNotRecordedProbability();
                if (ptypes.length == 1 && notRecordedTypeProbability == 0) {
                    if (optimisticOpts.inlineMonomorphicCalls()) {
                        RiResolvedType type = ptypes[0].type;
                        RiResolvedMethod concrete = type.resolveMethodImpl(targetMethod);
                        if (checkTargetConditions(invoke, concrete, optimisticOpts)) {
                            double weight = callback == null ? 0 : callback.inliningWeight(parent, concrete, invoke);
                            return new TypeGuardInlineInfo(invoke, weight, level, concrete, type);
                        }

                        Debug.log("not inlining %s because method can't be inlined", methodName(targetMethod, invoke));
                        return null;
                    } else {
                        Debug.log("not inlining %s because GraalOptions.InlineMonomorphicCalls == false", methodName(targetMethod, invoke));
                        return null;
                    }
                } else {
                    invoke.setMegamorphic(true);
                    if (optimisticOpts.inlinePolymorphicCalls() && notRecordedTypeProbability == 0 || optimisticOpts.inlineMegamorphicCalls() && notRecordedTypeProbability > 0) {
                        // TODO (chaeubl) inlining of multiple methods should work differently
                        // 1. check which methods can be inlined
                        // 2. for those methods, use weight and probability to compute which of them should be inlined
                        // 3. do the inlining
                        //    a) all seen methods can be inlined -> do so and guard with deopt
                        //    b) some methods can be inlined -> inline them and fall back to invocation if violated
                        // TODO (chaeubl) sort types by probability

                        // determine concrete methods and map type to specific method
                        ArrayList<RiResolvedMethod> concreteMethods = new ArrayList<>();
                        int[] typesToConcretes = new int[ptypes.length];
                        for (int i = 0; i < ptypes.length; i++) {
                            RiResolvedMethod concrete = ptypes[i].type.resolveMethodImpl(targetMethod);

                            int index = concreteMethods.indexOf(concrete);
                            if (index < 0) {
                                index = concreteMethods.size();
                                concreteMethods.add(concrete);
                            }
                            typesToConcretes[i] = index;
                        }

                        double totalWeight = 0;
                        boolean canInline = true;
                        for (RiResolvedMethod concrete: concreteMethods) {
                            if (!checkTargetConditions(invoke, concrete, optimisticOpts)) {
                                canInline = false;
                                break;
                            }
                            totalWeight += callback == null ? 0 : callback.inliningWeight(parent, concrete, invoke);
                        }

                        if (canInline) {
                            return new MultiTypeGuardInlineInfo(invoke, totalWeight, level, concreteMethods, ptypes, typesToConcretes, notRecordedTypeProbability);
                        } else {
                            Debug.log("not inlining %s because it is a polymorphic method call and at least one invoked method cannot be inlined", methodName(targetMethod, invoke));
                            return null;
                        }
                    } else {
                        if (!optimisticOpts.inlinePolymorphicCalls() && notRecordedTypeProbability == 0) {
                            Debug.log("not inlining %s because GraalOptions.InlinePolymorphicCalls == false", methodName(targetMethod, invoke));
                        } else {
                            Debug.log("not inlining %s because GraalOptions.InlineMegamorphicCalls == false", methodName(targetMethod, invoke));
                        }
                        return null;
                    }
                }
            }

            Debug.log("not inlining %s because no types/probabilities were recorded", methodName(targetMethod, invoke));
            return null;
        } else {
            Debug.log("not inlining %s because no type profile exists", methodName(targetMethod, invoke));
            return null;
        }
    }

    private static ValueNode createAnchoredReceiver(StructuredGraph graph, FixedNode anchor, RiResolvedType commonType, ValueNode receiver) {
        // to avoid that floating reads on receiver fields float above the type check
        return graph.unique(new PiNode(receiver, anchor, StampFactory.declaredNonNull(commonType)));
    }

    private static boolean checkInvokeConditions(Invoke invoke) {
        if (invoke.stateAfter() == null) {
            Debug.log("not inlining %s because the invoke has no after state", methodName(invoke.callTarget().targetMethod(), invoke));
            return false;
        }
        if (invoke.predecessor() == null) {
            Debug.log("not inlining %s because the invoke is dead code", methodName(invoke.callTarget().targetMethod(), invoke));
            return false;
        }
        if (!invoke.useForInlining()) {
            Debug.log("not inlining %s because invoke is marked to be not used for inlining", methodName(invoke.callTarget().targetMethod(), invoke));
            return false;
        }
        return true;
    }

    private static boolean checkTargetConditions(Invoke invoke, RiMethod method, OptimisticOptimizations optimisticOpts) {
        if (method == null) {
            Debug.log("not inlining because method is not resolved");
            return false;
        }
        if (!(method instanceof RiResolvedMethod)) {
            Debug.log("not inlining %s because it is unresolved", method.toString());
            return false;
        }
        RiResolvedMethod resolvedMethod = (RiResolvedMethod) method;
        if (Modifier.isNative(resolvedMethod.accessFlags())) {
            Debug.log("not inlining %s because it is a native method", methodName(resolvedMethod, invoke));
            return false;
        }
        if (Modifier.isAbstract(resolvedMethod.accessFlags())) {
            Debug.log("not inlining %s because it is an abstract method", methodName(resolvedMethod, invoke));
            return false;
        }
        if (!resolvedMethod.holder().isInitialized()) {
            Debug.log("not inlining %s because of non-initialized class", methodName(resolvedMethod, invoke));
            return false;
        }
        if (!resolvedMethod.canBeInlined()) {
            Debug.log("not inlining %s because it is marked non-inlinable", methodName(resolvedMethod, invoke));
            return false;
        }
        if (computeRecursiveInliningLevel(invoke.stateAfter(), (RiResolvedMethod) method) > GraalOptions.MaximumRecursiveInlining) {
            Debug.log("not inlining %s because it exceeds the maximum recursive inlining depth", methodName(resolvedMethod, invoke));
            return false;
        }
        OptimisticOptimizations calleeOpts = new OptimisticOptimizations(resolvedMethod);
        if (calleeOpts.lessOptimisticThan(optimisticOpts)) {
            Debug.log("not inlining %s because callee uses less optimistic optimizations than caller", methodName(resolvedMethod, invoke));
            return false;
        }

        return true;
    }

    private static int computeRecursiveInliningLevel(FrameState state, RiResolvedMethod method) {
        assert state != null;

        int count = 0;
        FrameState curState = state;
        while (curState != null) {
            if (curState.method() == method) {
                count++;
            }
            curState = curState.outerFrameState();
        }
        return count;
    }

    /**
     * Performs an actual inlining, thereby replacing the given invoke with the given inlineGraph.
     *
     * @param invoke the invoke that will be replaced
     * @param inlineGraph the graph that the invoke will be replaced with
     * @param receiverNullCheck true if a null check needs to be generated for non-static inlinings, false if no such check is required
     */
    public static void inline(Invoke invoke, StructuredGraph inlineGraph, boolean receiverNullCheck) {
        NodeInputList<ValueNode> parameters = invoke.callTarget().arguments();
        StructuredGraph graph = (StructuredGraph) invoke.node().graph();

        FrameState stateAfter = invoke.stateAfter();
        assert stateAfter.isAlive();

        IdentityHashMap<Node, Node> replacements = new IdentityHashMap<>();
        ArrayList<Node> nodes = new ArrayList<>();
        ReturnNode returnNode = null;
        UnwindNode unwindNode = null;
        StartNode entryPointNode = inlineGraph.start();
        FixedNode firstCFGNode = entryPointNode.next();
        for (Node node : inlineGraph.getNodes()) {
            if (node == entryPointNode || node == entryPointNode.stateAfter()) {
                // Do nothing.
            } else if (node instanceof LocalNode) {
                replacements.put(node, parameters.get(((LocalNode) node).index()));
            } else {
                nodes.add(node);
                if (node instanceof ReturnNode) {
                    returnNode = (ReturnNode) node;
                } else if (node instanceof UnwindNode) {
                    unwindNode = (UnwindNode) node;
                }
            }
        }

        assert invoke.node().successors().first() != null : invoke;
        assert invoke.node().predecessor() != null;

        Map<Node, Node> duplicates = graph.addDuplicates(nodes, replacements);
        FixedNode firstCFGNodeDuplicate = (FixedNode) duplicates.get(firstCFGNode);
        if (receiverNullCheck) {
            receiverNullCheck(invoke);
        }
        invoke.node().replaceAtPredecessors(firstCFGNodeDuplicate);

        FrameState stateAtExceptionEdge = null;
        if (invoke instanceof InvokeWithExceptionNode) {
            InvokeWithExceptionNode invokeWithException = ((InvokeWithExceptionNode) invoke);
            if (unwindNode != null) {
                assert unwindNode.predecessor() != null;
                assert invokeWithException.exceptionEdge().successors().count() == 1;
                ExceptionObjectNode obj = (ExceptionObjectNode) invokeWithException.exceptionEdge().next();
                stateAtExceptionEdge = obj.stateAfter();
                UnwindNode unwindDuplicate = (UnwindNode) duplicates.get(unwindNode);
                obj.replaceAtUsages(unwindDuplicate.exception());
                unwindDuplicate.clearInputs();
                Node n = obj.next();
                obj.setNext(null);
                unwindDuplicate.replaceAndDelete(n);
            } else {
                invokeWithException.killExceptionEdge();
            }
        } else {
            if (unwindNode != null) {
                UnwindNode unwindDuplicate = (UnwindNode) duplicates.get(unwindNode);
                DeoptimizeNode deoptimizeNode = new DeoptimizeNode(RiDeoptAction.InvalidateRecompile, RiDeoptReason.NotCompiledExceptionHandler, invoke.leafGraphId());
                unwindDuplicate.replaceAndDelete(graph.add(deoptimizeNode));
                // move the deopt upwards if there is a monitor exit that tries to use the "after exception" frame state
                // (because there is no "after exception" frame state!)
                if (deoptimizeNode.predecessor() instanceof MonitorExitNode) {
                    MonitorExitNode monitorExit = (MonitorExitNode) deoptimizeNode.predecessor();
                    if (monitorExit.stateAfter() != null && monitorExit.stateAfter().bci == FrameState.AFTER_EXCEPTION_BCI) {
                        FrameState monitorFrameState = monitorExit.stateAfter();
                        graph.removeFixed(monitorExit);
                        monitorFrameState.safeDelete();
                    }
                }
            }
        }

        FrameState outerFrameState = null;
        double invokeProbability = invoke.node().probability();
        for (Node node : duplicates.values()) {
            if (GraalOptions.ProbabilityAnalysis) {
                if (node instanceof FixedNode) {
                    FixedNode fixed = (FixedNode) node;
                    double newProbability = fixed.probability() * invokeProbability;
                    if (GraalOptions.LimitInlinedProbability) {
                        newProbability = Math.min(newProbability, invokeProbability);
                    }
                    fixed.setProbability(newProbability);
                }
            }
            if (node instanceof FrameState) {
                FrameState frameState = (FrameState) node;
                assert frameState.bci != FrameState.BEFORE_BCI;
                if (frameState.bci == FrameState.AFTER_BCI) {
                    frameState.replaceAndDelete(stateAfter);
                } else if (frameState.bci == FrameState.AFTER_EXCEPTION_BCI) {
                    if (frameState.isAlive()) {
                        assert stateAtExceptionEdge != null;
                        frameState.replaceAndDelete(stateAtExceptionEdge);
                    } else {
                        assert stateAtExceptionEdge == null;
                    }
                } else {
                    if (outerFrameState == null) {
                        outerFrameState = stateAfter.duplicateModified(invoke.bci(), stateAfter.rethrowException(), invoke.node().kind());
                        outerFrameState.setDuringCall(true);
                    }
                    frameState.setOuterFrameState(outerFrameState);
                }
            }
        }

        Node returnValue = null;
        if (returnNode != null) {
            if (returnNode.result() instanceof LocalNode) {
                returnValue = replacements.get(returnNode.result());
            } else {
                returnValue = duplicates.get(returnNode.result());
            }
            invoke.node().replaceAtUsages(returnValue);
            Node returnDuplicate = duplicates.get(returnNode);
            returnDuplicate.clearInputs();
            Node n = invoke.next();
            invoke.setNext(null);
            returnDuplicate.replaceAndDelete(n);
        }

        invoke.node().clearInputs();
        invoke.node().replaceAtUsages(null);
        GraphUtil.killCFG(invoke.node());

        if (stateAfter.usages().isEmpty()) {
            stateAfter.safeDelete();
        }
    }

    /**
     * Performs replacement of a node with a snippet graph.
     * @param replacee the node that will be replaced
     * @param anchor the control flow replacee
     * @param snippetGraph the graph that the replacee will be replaced with
     * @param explodeLoops specifies if all the loops in the snippet graph are counted loops that must be completely unrolled
     * @param args
     */
    public static void inlineSnippet(final RiRuntime runtime,
                    final Node replacee,
                    final FixedWithNextNode anchor,
                    final StructuredGraph snippetGraph,
                    final boolean explodeLoops,
                    final IsImmutablePredicate immutabilityPredicate,
                    final Object... args) {
        Debug.scope("InliningSnippet", snippetGraph.method(), new Runnable() {
            @Override
            public void run() {
                inlineSnippet0(runtime, replacee, anchor, snippetGraph, explodeLoops, immutabilityPredicate, args);
            }
        });
    }
    private static void inlineSnippet0(RiRuntime runtime,
                    Node replacee,
                    FixedWithNextNode anchor,
                    StructuredGraph snippetGraph,
                    boolean explodeLoops,
                    IsImmutablePredicate immutabilityPredicate,
                    Object... args) {

        Debug.dump(replacee.graph(), "Before lowering %s", replacee);

        // Copy snippet graph, replacing parameters with given args in the process
        StructuredGraph snippetCopy = new StructuredGraph(snippetGraph.name, snippetGraph.method());
        IdentityHashMap<Node, Node> replacements = new IdentityHashMap<>();
        replacements.put(snippetGraph.start(), snippetCopy.start());
        int localCount = 0;
        for (LocalNode local : snippetGraph.getNodes(LocalNode.class)) {
            int index = local.index();
            if (args[index] instanceof CiConstant) {
                CiConstant arg = (CiConstant) args[index];
                assert arg.kind.stackKind() == local.kind() : arg.kind + " != " + local.kind();
                ConstantNode argNode = ConstantNode.forCiConstant(arg, runtime, snippetCopy);
                replacements.put(local, argNode);
                args[index] = null;
            } else {
                assert args[index] instanceof ValueNode;
            }
            localCount++;
        }
        assert localCount == args.length : "snippet argument count mismatch";
        snippetCopy.addDuplicates(snippetGraph.getNodes(), replacements);
        if (!replacements.isEmpty()) {
            new CanonicalizerPhase(null, runtime, null, 0, immutabilityPredicate).apply(snippetCopy);
        }

        // Explode all loops in the snippet if requested
        if (explodeLoops && snippetCopy.hasLoops()) {
            ControlFlowGraph cfg = ControlFlowGraph.compute(snippetCopy, true, true, false, false);
            for (Loop loop : cfg.getLoops()) {
                LoopBeginNode loopBegin = loop.loopBegin();
                SuperBlock wholeLoop = LoopTransformUtil.wholeLoop(loop);
                Debug.dump(snippetCopy, "Before exploding loop %s", loopBegin);
                int peel = 0;
                while (!loopBegin.isDeleted()) {
                    int mark = snippetCopy.getMark();
                    LoopTransformUtil.peel(loop, wholeLoop);
                    Debug.dump(snippetCopy, "After peel %d", peel);
                    new CanonicalizerPhase(null, runtime, null, mark, immutabilityPredicate).apply(snippetCopy);
                    peel++;
                }
                Debug.dump(snippetCopy, "After exploding loop %s", loopBegin);
            }
            new DeadCodeEliminationPhase().apply(snippetCopy);
        }

        // Gather the nodes in the snippet that are to be inlined
        ArrayList<Node> nodes = new ArrayList<>();
        ReturnNode returnNode = null;
        StartNode entryPointNode = snippetCopy.start();
        FixedNode firstCFGNode = entryPointNode.next();
        replacements.clear();
        for (Node node : snippetCopy.getNodes()) {
            if (node == entryPointNode || node == entryPointNode.stateAfter()) {
                // Do nothing.
            } else if (node instanceof LocalNode) {
                LocalNode local = (LocalNode) node;
                int index = local.index();
                assert args[index] instanceof ValueNode;
                ValueNode arg = (ValueNode) args[index];
                assert arg.kind() == local.kind();
                replacements.put(node, arg);
                args[index] = null;
            } else {
                nodes.add(node);
                if (node instanceof ReturnNode) {
                    returnNode = (ReturnNode) node;
                }
            }
        }

        // Inline the gathered snippet nodes
        StructuredGraph graph = (StructuredGraph) replacee.graph();
        int mark = graph.getMark();
        Map<Node, Node> duplicates = graph.addDuplicates(nodes, replacements);
        Debug.dump(graph, "After inlining snippet %s", snippetCopy.method());

        // Remove all frame states from the inlined snippet graph. Snippets must be atomic (i.e. free
        // of side-effects that prevent deoptimizing to a point before the snippet).
        for (Node node : graph.getNewNodes(mark)) {
            if (node instanceof StateSplit) {
                StateSplit stateSplit = (StateSplit) node;
                FrameState frameState = stateSplit.stateAfter();
                assert !stateSplit.hasSideEffect() : "snippets cannot contain side-effecting node " + node + "\n    " + frameState.toString(Verbosity.Debugger);
                if (frameState != null) {
                    stateSplit.setStateAfter(null);
                }
            }
        }

        Debug.dump(graph, "After removing frame states");

        // Rewire the control flow graph around the replacee
        FixedNode firstCFGNodeDuplicate = (FixedNode) duplicates.get(firstCFGNode);
        anchor.replaceAtPredecessors(firstCFGNodeDuplicate);
        FixedNode next = anchor.next();
        anchor.setNext(null);

        // Replace all usages of the replacee with the value returned by the snippet
        Node returnValue = null;
        if (returnNode != null) {
            if (returnNode.result() instanceof LocalNode) {
                returnValue = replacements.get(returnNode.result());
            } else {
                returnValue = duplicates.get(returnNode.result());
            }
            assert returnValue != null || replacee.usages().isEmpty();
            replacee.replaceAtUsages(returnValue);

            Node returnDuplicate = duplicates.get(returnNode);
            returnDuplicate.clearInputs();
            returnDuplicate.replaceAndDelete(next);
        }

        // Remove the replacee from its graph
        replacee.clearInputs();
        replacee.replaceAtUsages(null);
        if (replacee instanceof FixedNode) {
            GraphUtil.killCFG((FixedNode) replacee);
        } else {
            replacee.safeDelete();
        }
    }

    public static void receiverNullCheck(Invoke invoke) {
        MethodCallTargetNode callTarget = invoke.callTarget();
        StructuredGraph graph = (StructuredGraph) invoke.graph();
        NodeInputList<ValueNode> parameters = callTarget.arguments();
        ValueNode firstParam = parameters.size() <= 0 ? null : parameters.get(0);
        if (!callTarget.isStatic() && firstParam.kind() == CiKind.Object && !firstParam.stamp().nonNull()) {
            graph.addBeforeFixed(invoke.node(), graph.add(new FixedGuardNode(graph.unique(new IsNullNode(firstParam)), RiDeoptReason.ClassCastException, RiDeoptAction.InvalidateReprofile, true, invoke.leafGraphId())));
        }
    }
}
