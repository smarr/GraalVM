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
package com.oracle.graal.replacements;

import static com.oracle.graal.phases.common.DeadCodeEliminationPhase.Optionality.*;
import static com.oracle.graal.replacements.SnippetTemplate.AbstractTemplates.*;
import static java.util.FormattableFlags.*;
import static jdk.internal.jvmci.debug.Debug.*;
import static jdk.internal.jvmci.meta.LocationIdentity.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.*;

import jdk.internal.jvmci.code.*;
import jdk.internal.jvmci.common.*;
import jdk.internal.jvmci.debug.*;
import jdk.internal.jvmci.debug.Debug.Scope;
import jdk.internal.jvmci.meta.*;

import com.oracle.graal.api.replacements.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.Graph.Mark;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.Node;
import com.oracle.graal.loop.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.StructuredGraph.AllowAssumptions;
import com.oracle.graal.nodes.StructuredGraph.GuardsStage;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.memory.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.phases.common.*;
import com.oracle.graal.phases.common.FloatingReadPhase.MemoryMapImpl;
import com.oracle.graal.phases.common.inlining.*;
import com.oracle.graal.phases.tiers.*;
import com.oracle.graal.phases.util.*;
import com.oracle.graal.replacements.Snippet.ConstantParameter;
import com.oracle.graal.replacements.Snippet.VarargsParameter;
import com.oracle.graal.replacements.nodes.*;
import com.oracle.graal.word.*;

/**
 * A snippet template is a graph created by parsing a snippet method and then specialized by binding
 * constants to the snippet's {@link ConstantParameter} parameters.
 *
 * Snippet templates can be managed in a cache maintained by {@link AbstractTemplates}.
 */
public class SnippetTemplate {

    // Checkstyle: stop
    public static boolean LAZY_SNIPPETS = true;

    // Checkstyle: resume

    /**
     * Holds the {@link ResolvedJavaMethod} of the snippet, together with some information about the
     * method that needs to be computed only once. The {@link SnippetInfo} should be created once
     * per snippet and then cached.
     */
    public abstract static class SnippetInfo {

        protected final ResolvedJavaMethod method;
        protected ResolvedJavaMethod original;
        protected final LocationIdentity[] privateLocations;

        /**
         * Lazily constructed parts of {@link SnippetInfo}.
         */
        static class Lazy {
            public Lazy(ResolvedJavaMethod method) {
                int count = method.getSignature().getParameterCount(false);
                constantParameters = new boolean[count];
                varargsParameters = new boolean[count];
                for (int i = 0; i < count; i++) {
                    constantParameters[i] = method.getParameterAnnotation(ConstantParameter.class, i) != null;
                    varargsParameters[i] = method.getParameterAnnotation(VarargsParameter.class, i) != null;

                    assert !constantParameters[i] || !varargsParameters[i] : "Parameter cannot be annotated with both @" + ConstantParameter.class.getSimpleName() + " and @" +
                                    VarargsParameter.class.getSimpleName();
                }

                // Retrieve the names only when assertions are turned on.
                assert initNames(method, count);
            }

            final boolean[] constantParameters;
            final boolean[] varargsParameters;

            /**
             * The parameter names, taken from the local variables table. Only used for assertion
             * checking, so use only within an assert statement.
             */
            String[] names;

            private boolean initNames(ResolvedJavaMethod method, int parameterCount) {
                names = new String[parameterCount];
                int slotIdx = 0;
                for (int i = 0; i < names.length; i++) {
                    names[i] = method.getLocalVariableTable().getLocal(slotIdx, 0).getName();

                    Kind kind = method.getSignature().getParameterKind(i);
                    slotIdx += kind.getSlotCount();
                }
                return true;
            }

        }

        /**
         * Times instantiations of all templates derived form this snippet.
         *
         * @see SnippetTemplate#instantiationTimer
         */
        private final DebugTimer instantiationTimer;

        /**
         * Counts instantiations of all templates derived from this snippet.
         *
         * @see SnippetTemplate#instantiationCounter
         */
        private final DebugMetric instantiationCounter;

        protected abstract Lazy lazy();

        protected SnippetInfo(ResolvedJavaMethod method, LocationIdentity[] privateLocations) {
            this.method = method;
            this.privateLocations = SnippetCounterNode.addSnippetCounters(privateLocations);
            instantiationCounter = Debug.metric("SnippetInstantiationCount[%s]", method.getName());
            instantiationTimer = Debug.timer("SnippetInstantiationTime[%s]", method.getName());
            assert method.isStatic() : "snippet method must be static: " + method.format("%H.%n");
        }

        private int templateCount;

        void notifyNewTemplate() {
            templateCount++;
            if (templateCount == MaxTemplatesPerSnippet) {
                TTY.print("WARNING: Exceeded %d templates for snippet %s%n" + "         Adjust maximum with %s system property%n", MaxTemplatesPerSnippet, method.format("%h.%n(%p)"),
                                MAX_TEMPLATES_PER_SNIPPET_PROPERTY_NAME);
            }
        }

        public ResolvedJavaMethod getMethod() {
            return method;
        }

        public int getParameterCount() {
            return lazy().constantParameters.length;
        }

        public void setOriginalMethod(ResolvedJavaMethod original) {
            this.original = original;
        }

        public boolean isConstantParameter(int paramIdx) {
            return lazy().constantParameters[paramIdx];
        }

        public boolean isVarargsParameter(int paramIdx) {
            return lazy().varargsParameters[paramIdx];
        }

        public String getParameterName(int paramIdx) {
            String[] names = lazy().names;
            if (names != null) {
                return names[paramIdx];
            }
            return null;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + ":" + method.format("%h.%n");
        }
    }

    protected static class LazySnippetInfo extends SnippetInfo {
        protected final AtomicReference<Lazy> lazy = new AtomicReference<>(null);

        protected LazySnippetInfo(ResolvedJavaMethod method, LocationIdentity[] privateLocations) {
            super(method, privateLocations);
        }

        @Override
        protected Lazy lazy() {
            if (lazy.get() == null) {
                lazy.compareAndSet(null, new Lazy(method));
            }
            return lazy.get();
        }
    }

    protected static class EagerSnippetInfo extends SnippetInfo {
        protected final Lazy lazy;

        protected EagerSnippetInfo(ResolvedJavaMethod method, LocationIdentity[] privateLocations) {
            super(method, privateLocations);
            lazy = new Lazy(method);
        }

        @Override
        protected Lazy lazy() {
            return lazy;
        }
    }

    /**
     * Values that are bound to the snippet method parameters. The methods {@link #add},
     * {@link #addConst}, and {@link #addVarargs} must be called in the same order as in the
     * signature of the snippet method. The parameter name is passed to the add methods for
     * assertion checking, i.e., to enforce that the order matches. Which method needs to be called
     * depends on the annotation of the snippet method parameter:
     * <ul>
     * <li>Use {@link #add} for a parameter without an annotation. The value is bound when the
     * {@link SnippetTemplate} is {@link SnippetTemplate#instantiate instantiated}.
     * <li>Use {@link #addConst} for a parameter annotated with {@link ConstantParameter}. The value
     * is bound when the {@link SnippetTemplate} is {@link SnippetTemplate#SnippetTemplate created}.
     * <li>Use {@link #addVarargs} for an array parameter annotated with {@link VarargsParameter}. A
     * separate {@link SnippetTemplate} is {@link SnippetTemplate#SnippetTemplate created} for every
     * distinct array length. The actual values are bound when the {@link SnippetTemplate} is
     * {@link SnippetTemplate#instantiate instantiated}
     * </ul>
     */
    public static class Arguments implements Formattable {

        protected final SnippetInfo info;
        protected final CacheKey cacheKey;
        protected final Object[] values;
        protected final Stamp[] constStamps;
        protected boolean cacheable;

        protected int nextParamIdx;

        public Arguments(SnippetInfo info, GuardsStage guardsStage, LoweringTool.LoweringStage loweringStage) {
            this.info = info;
            this.cacheKey = new CacheKey(info, guardsStage, loweringStage);
            this.values = new Object[info.getParameterCount()];
            this.constStamps = new Stamp[info.getParameterCount()];
            this.cacheable = true;
        }

        public Arguments add(String name, Object value) {
            assert check(name, false, false);
            values[nextParamIdx] = value;
            nextParamIdx++;
            return this;
        }

        public Arguments addConst(String name, Object value) {
            assert value != null;
            return addConst(name, value, null);
        }

        public Arguments addConst(String name, Object value, Stamp stamp) {
            assert check(name, true, false);
            values[nextParamIdx] = value;
            constStamps[nextParamIdx] = stamp;
            cacheKey.setParam(nextParamIdx, value);
            nextParamIdx++;
            return this;
        }

        public Arguments addVarargs(String name, Class<?> componentType, Stamp argStamp, Object value) {
            assert check(name, false, true);
            Varargs varargs = new Varargs(componentType, argStamp, value);
            values[nextParamIdx] = varargs;
            // A separate template is necessary for every distinct array length
            cacheKey.setParam(nextParamIdx, varargs.length);
            nextParamIdx++;
            return this;
        }

        public void setCacheable(boolean cacheable) {
            this.cacheable = cacheable;
        }

        private boolean check(String name, boolean constParam, boolean varargsParam) {
            assert nextParamIdx < info.getParameterCount() : "too many parameters: " + name + "  " + this;
            assert info.getParameterName(nextParamIdx) == null || info.getParameterName(nextParamIdx).equals(name) : "wrong parameter name: " + name + "  " + this;
            assert constParam == info.isConstantParameter(nextParamIdx) : "Parameter " + (constParam ? "not " : "") + "annotated with @" + ConstantParameter.class.getSimpleName() + ": " + name +
                            "  " + this;
            assert varargsParam == info.isVarargsParameter(nextParamIdx) : "Parameter " + (varargsParam ? "not " : "") + "annotated with @" + VarargsParameter.class.getSimpleName() + ": " + name +
                            "  " + this;
            return true;
        }

        @Override
        public String toString() {
            StringBuilder result = new StringBuilder();
            result.append("Parameters<").append(info.method.format("%h.%n")).append(" [");
            String sep = "";
            for (int i = 0; i < info.getParameterCount(); i++) {
                result.append(sep);
                if (info.isConstantParameter(i)) {
                    result.append("const ");
                } else if (info.isVarargsParameter(i)) {
                    result.append("varargs ");
                }
                result.append(info.getParameterName(i)).append(" = ").append(values[i]);
                sep = ", ";
            }
            result.append(">");
            return result.toString();
        }

        public void formatTo(Formatter formatter, int flags, int width, int precision) {
            if ((flags & ALTERNATE) == 0) {
                formatter.format(applyFormattingFlagsAndWidth(toString(), flags, width));
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append(info.method.getName()).append('(');
                String sep = "";
                for (int i = 0; i < info.getParameterCount(); i++) {
                    if (info.isConstantParameter(i)) {
                        sb.append(sep);
                        if (info.getParameterName(i) != null) {
                            sb.append(info.getParameterName(i));
                        } else {
                            sb.append(i);
                        }
                        sb.append('=').append(values[i]);
                        sep = ", ";
                    }
                }
                sb.append(")");
                String string = sb.toString();
                if (string.indexOf('%') != -1) {
                    // Quote any % signs
                    string = string.replace("%", "%%");
                }
                formatter.format(applyFormattingFlagsAndWidth(string, flags & ~ALTERNATE, width));
            }
        }
    }

    /**
     * Wrapper for the prototype value of a {@linkplain VarargsParameter varargs} parameter.
     */
    static class Varargs {

        protected final Class<?> componentType;
        protected final Stamp stamp;
        protected final Object value;
        protected final int length;

        protected Varargs(Class<?> componentType, Stamp stamp, Object value) {
            this.componentType = componentType;
            this.stamp = stamp;
            this.value = value;
            if (value instanceof List) {
                this.length = ((List<?>) value).size();
            } else {
                this.length = Array.getLength(value);
            }
        }

        @Override
        public String toString() {
            if (value instanceof boolean[]) {
                return Arrays.toString((boolean[]) value);
            }
            if (value instanceof byte[]) {
                return Arrays.toString((byte[]) value);
            }
            if (value instanceof char[]) {
                return Arrays.toString((char[]) value);
            }
            if (value instanceof short[]) {
                return Arrays.toString((short[]) value);
            }
            if (value instanceof int[]) {
                return Arrays.toString((int[]) value);
            }
            if (value instanceof long[]) {
                return Arrays.toString((long[]) value);
            }
            if (value instanceof float[]) {
                return Arrays.toString((float[]) value);
            }
            if (value instanceof double[]) {
                return Arrays.toString((double[]) value);
            }
            if (value instanceof Object[]) {
                return Arrays.toString((Object[]) value);
            }
            return String.valueOf(value);
        }
    }

    @NodeInfo
    static final class VarargsPlaceholderNode extends FloatingNode implements ArrayLengthProvider {

        public static final NodeClass<VarargsPlaceholderNode> TYPE = NodeClass.create(VarargsPlaceholderNode.class);
        protected final Varargs varargs;

        public VarargsPlaceholderNode(Varargs varargs, MetaAccessProvider metaAccess) {
            super(TYPE, StampFactory.exactNonNull(metaAccess.lookupJavaType(varargs.componentType).getArrayClass()));
            this.varargs = varargs;
        }

        public ValueNode length() {
            return ConstantNode.forInt(varargs.length);
        }
    }

    static class CacheKey {

        private final ResolvedJavaMethod method;
        private final Object[] values;
        private final GuardsStage guardsStage;
        private final LoweringTool.LoweringStage loweringStage;
        private int hash;

        protected CacheKey(SnippetInfo info, GuardsStage guardsStage, LoweringTool.LoweringStage loweringStage) {
            this.method = info.method;
            this.guardsStage = guardsStage;
            this.loweringStage = loweringStage;
            this.values = new Object[info.getParameterCount()];
            this.hash = info.method.hashCode() + 31 * guardsStage.ordinal();
        }

        protected void setParam(int paramIdx, Object value) {
            values[paramIdx] = value;
            hash = (hash * 31) ^ (value == null ? 0 : value.hashCode());
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof CacheKey)) {
                return false;
            }
            CacheKey other = (CacheKey) obj;
            if (!method.equals(other.method)) {
                return false;
            }
            if (guardsStage != other.guardsStage || loweringStage != other.loweringStage) {
                return false;
            }
            for (int i = 0; i < values.length; i++) {
                if (values[i] != null && !values[i].equals(other.values[i])) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final DebugTimer SnippetTemplateCreationTime = Debug.timer("SnippetTemplateCreationTime");
    private static final DebugMetric SnippetTemplates = Debug.metric("SnippetTemplateCount");

    private static final String MAX_TEMPLATES_PER_SNIPPET_PROPERTY_NAME = "graal.maxTemplatesPerSnippet";
    private static final int MaxTemplatesPerSnippet = Integer.getInteger(MAX_TEMPLATES_PER_SNIPPET_PROPERTY_NAME, 50);

    /**
     * Base class for snippet classes. It provides a cache for {@link SnippetTemplate}s.
     */
    public abstract static class AbstractTemplates implements com.oracle.graal.api.replacements.SnippetTemplateCache {

        static final boolean UseSnippetTemplateCache = Boolean.parseBoolean(System.getProperty("graal.useSnippetTemplateCache", "true"));

        protected final Providers providers;
        protected final SnippetReflectionProvider snippetReflection;
        protected final TargetDescription target;
        private final ConcurrentHashMap<CacheKey, SnippetTemplate> templates;

        protected AbstractTemplates(Providers providers, SnippetReflectionProvider snippetReflection, TargetDescription target) {
            this.providers = providers;
            this.snippetReflection = snippetReflection;
            this.target = target;
            if (UseSnippetTemplateCache) {
                this.templates = new ConcurrentHashMap<>();
            } else {
                this.templates = null;
            }
        }

        public static Method findMethod(Class<? extends Snippets> declaringClass, String methodName, Method except) {
            for (Method m : declaringClass.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && !m.equals(except)) {
                    return m;
                }
            }
            return null;
        }

        /**
         * Finds the unique method in {@code declaringClass} named {@code methodName} annotated by
         * {@link Snippet} and returns a {@link SnippetInfo} value describing it. There must be
         * exactly one snippet method in {@code declaringClass}.
         */
        protected SnippetInfo snippet(Class<? extends Snippets> declaringClass, String methodName, LocationIdentity... privateLocations) {
            assert methodName != null;
            Method method = findMethod(declaringClass, methodName, null);
            assert method != null : "did not find @" + Snippet.class.getSimpleName() + " method in " + declaringClass + " named " + methodName;
            assert method.getAnnotation(Snippet.class) != null : method + " must be annotated with @" + Snippet.class.getSimpleName();
            assert findMethod(declaringClass, methodName, method) == null : "found more than one method named " + methodName + " in " + declaringClass;
            ResolvedJavaMethod javaMethod = providers.getMetaAccess().lookupJavaMethod(method);
            providers.getReplacements().registerSnippet(javaMethod);
            if (LAZY_SNIPPETS) {
                return new LazySnippetInfo(javaMethod, privateLocations);
            } else {
                return new EagerSnippetInfo(javaMethod, privateLocations);
            }
        }

        /**
         * Gets a template for a given key, creating it first if necessary.
         */
        protected SnippetTemplate template(final Arguments args) {
            SnippetTemplate template = UseSnippetTemplateCache && args.cacheable ? templates.get(args.cacheKey) : null;
            if (template == null) {
                SnippetTemplates.increment();
                try (DebugCloseable a = SnippetTemplateCreationTime.start(); Scope s = Debug.scope("SnippetSpecialization", args.info.method)) {
                    template = new SnippetTemplate(providers, snippetReflection, args);
                    if (UseSnippetTemplateCache && args.cacheable) {
                        templates.put(args.cacheKey, template);
                    }
                } catch (Throwable e) {
                    throw Debug.handle(e);
                }
            }
            return template;
        }
    }

    // These values must be compared with equals() not '==' to support replay compilation.
    private static final Object UNUSED_PARAMETER = "UNUSED_PARAMETER";
    private static final Object CONSTANT_PARAMETER = "CONSTANT_PARAMETER";

    /**
     * Determines if any parameter of a given method is annotated with {@link ConstantParameter}.
     */
    public static boolean hasConstantParameter(ResolvedJavaMethod method) {
        for (ConstantParameter p : method.getParameterAnnotations(ConstantParameter.class)) {
            if (p != null) {
                return true;
            }
        }
        return false;
    }

    private final SnippetReflectionProvider snippetReflection;

    /**
     * Creates a snippet template.
     */
    protected SnippetTemplate(final Providers providers, SnippetReflectionProvider snippetReflection, Arguments args) {
        this.snippetReflection = snippetReflection;
        this.info = args.info;

        Object[] constantArgs = getConstantArgs(args);
        StructuredGraph snippetGraph = providers.getReplacements().getSnippet(args.info.method, args.info.original, constantArgs);
        instantiationTimer = Debug.timer("SnippetTemplateInstantiationTime[%#s]", args);
        instantiationCounter = Debug.metric("SnippetTemplateInstantiationCount[%#s]", args);

        ResolvedJavaMethod method = snippetGraph.method();
        Signature signature = method.getSignature();

        PhaseContext phaseContext = new PhaseContext(providers);

        // Copy snippet graph, replacing constant parameters with given arguments
        final StructuredGraph snippetCopy = new StructuredGraph(snippetGraph.name, snippetGraph.method(), AllowAssumptions.NO);
        if (!snippetGraph.isInlinedMethodRecordingEnabled()) {
            snippetCopy.disableInlinedMethodRecording();
        }
        if (!snippetGraph.isUnsafeAccessTrackingEnabled()) {
            snippetCopy.disableUnsafeAccessTracking();
        }

        Map<Node, Node> nodeReplacements = Node.newIdentityMap();
        nodeReplacements.put(snippetGraph.start(), snippetCopy.start());

        MetaAccessProvider metaAccess = providers.getMetaAccess();
        assert checkTemplate(metaAccess, args, method, signature);

        int parameterCount = args.info.getParameterCount();
        VarargsPlaceholderNode[] placeholders = new VarargsPlaceholderNode[parameterCount];

        for (int i = 0; i < parameterCount; i++) {
            if (args.info.isConstantParameter(i)) {
                Object arg = args.values[i];
                Kind kind = signature.getParameterKind(i);
                ConstantNode constantNode;
                if (arg instanceof Constant) {
                    Stamp stamp = args.constStamps[i];
                    if (stamp == null) {
                        assert arg instanceof JavaConstant : "could not determine type of constant " + arg;
                        constantNode = ConstantNode.forConstant((JavaConstant) arg, metaAccess, snippetCopy);
                    } else {
                        constantNode = ConstantNode.forConstant(stamp, (Constant) arg, metaAccess, snippetCopy);
                    }
                } else {
                    constantNode = ConstantNode.forConstant(snippetReflection.forBoxed(kind, arg), metaAccess, snippetCopy);
                }
                nodeReplacements.put(snippetGraph.getParameter(i), constantNode);
            } else if (args.info.isVarargsParameter(i)) {
                Varargs varargs = (Varargs) args.values[i];
                VarargsPlaceholderNode placeholder = snippetCopy.unique(new VarargsPlaceholderNode(varargs, providers.getMetaAccess()));
                nodeReplacements.put(snippetGraph.getParameter(i), placeholder);
                placeholders[i] = placeholder;
            }
        }
        snippetCopy.addDuplicates(snippetGraph.getNodes(), snippetGraph, snippetGraph.getNodeCount(), nodeReplacements);

        Debug.dump(snippetCopy, "Before specialization");

        // Gather the template parameters
        parameters = new Object[parameterCount];
        for (int i = 0; i < parameterCount; i++) {
            if (args.info.isConstantParameter(i)) {
                parameters[i] = CONSTANT_PARAMETER;
            } else if (args.info.isVarargsParameter(i)) {
                assert snippetCopy.getParameter(i) == null;
                Varargs varargs = (Varargs) args.values[i];
                int length = varargs.length;
                ParameterNode[] params = new ParameterNode[length];
                Stamp stamp = varargs.stamp;
                for (int j = 0; j < length; j++) {
                    // Use a decimal friendly numbering make it more obvious how values map
                    assert parameterCount < 10000;
                    int idx = (i + 1) * 10000 + j;
                    assert idx >= parameterCount : "collision in parameter numbering";
                    ParameterNode local = snippetCopy.unique(new ParameterNode(idx, stamp));
                    params[j] = local;
                }
                parameters[i] = params;

                VarargsPlaceholderNode placeholder = placeholders[i];
                assert placeholder != null;
                for (Node usage : placeholder.usages().snapshot()) {
                    if (usage instanceof LoadIndexedNode) {
                        LoadIndexedNode loadIndexed = (LoadIndexedNode) usage;
                        Debug.dump(snippetCopy, "Before replacing %s", loadIndexed);
                        LoadSnippetVarargParameterNode loadSnippetParameter = snippetCopy.add(new LoadSnippetVarargParameterNode(params, loadIndexed.index(), loadIndexed.stamp()));
                        snippetCopy.replaceFixedWithFixed(loadIndexed, loadSnippetParameter);
                        Debug.dump(snippetCopy, "After replacing %s", loadIndexed);
                    } else if (usage instanceof StoreIndexedNode) {
                        // The template lowering doesn't really treat this as an array so you can't
                        // store back into the varargs. Allocate your own array if you really need
                        // this and EA should eliminate it.
                        throw new JVMCIError("Can't store into VarargsParameter array");
                    }
                }
            } else {
                ParameterNode local = snippetCopy.getParameter(i);
                if (local == null) {
                    // Parameter value was eliminated
                    parameters[i] = UNUSED_PARAMETER;
                } else {
                    parameters[i] = local;
                }
            }
        }

        // Do any required loop explosion
        boolean exploded = false;
        do {
            exploded = false;
            ExplodeLoopNode explodeLoop = snippetCopy.getNodes().filter(ExplodeLoopNode.class).first();
            if (explodeLoop != null) { // Earlier canonicalization may have removed the loop
                // altogether
                LoopBeginNode loopBegin = explodeLoop.findLoopBegin();
                if (loopBegin != null) {
                    LoopEx loop = new LoopsData(snippetCopy).loop(loopBegin);
                    Mark mark = snippetCopy.getMark();
                    LoopTransformations.fullUnroll(loop, phaseContext, new CanonicalizerPhase());
                    new CanonicalizerPhase().applyIncremental(snippetCopy, phaseContext, mark);
                    loop.deleteUnusedNodes();
                }
                GraphUtil.removeFixedWithUnusedInputs(explodeLoop);
                exploded = true;
            }
        } while (exploded);

        GuardsStage guardsStage = args.cacheKey.guardsStage;
        // Perform lowering on the snippet
        if (!guardsStage.allowsFloatingGuards()) {
            new GuardLoweringPhase().apply(snippetCopy, null);
        }
        snippetCopy.setGuardsStage(guardsStage);
        try (Scope s = Debug.scope("LoweringSnippetTemplate", snippetCopy)) {
            new LoweringPhase(new CanonicalizerPhase(), args.cacheKey.loweringStage).apply(snippetCopy, phaseContext);
        } catch (Throwable e) {
            throw Debug.handle(e);
        }

        ArrayList<StateSplit> curSideEffectNodes = new ArrayList<>();
        ArrayList<DeoptimizingNode> curDeoptNodes = new ArrayList<>();
        ArrayList<ValueNode> curStampNodes = new ArrayList<>();
        for (Node node : snippetCopy.getNodes()) {
            if (node instanceof ValueNode && ((ValueNode) node).stamp() == StampFactory.forNodeIntrinsic()) {
                curStampNodes.add((ValueNode) node);
            }
            if (node instanceof StateSplit) {
                StateSplit stateSplit = (StateSplit) node;
                FrameState frameState = stateSplit.stateAfter();
                if (stateSplit.hasSideEffect()) {
                    curSideEffectNodes.add((StateSplit) node);
                }
                if (frameState != null) {
                    stateSplit.setStateAfter(null);
                }
            }
            if (node instanceof DeoptimizingNode) {
                DeoptimizingNode deoptNode = (DeoptimizingNode) node;
                if (deoptNode.canDeoptimize()) {
                    curDeoptNodes.add(deoptNode);
                }
            }
        }

        new DeadCodeEliminationPhase(Required).apply(snippetCopy);

        assert checkAllVarargPlaceholdersAreDeleted(parameterCount, placeholders);

        new FloatingReadPhase(true, true).apply(snippetCopy);

        MemoryAnchorNode anchor = snippetCopy.add(new MemoryAnchorNode());
        snippetCopy.start().replaceAtUsages(InputType.Memory, anchor);

        this.snippet = snippetCopy;

        Debug.dump(snippet, "SnippetTemplate after fixing memory anchoring");

        StartNode entryPointNode = snippet.start();
        if (anchor.hasNoUsages()) {
            anchor.safeDelete();
            this.memoryAnchor = null;
        } else {
            snippetCopy.addAfterFixed(snippetCopy.start(), anchor);
            this.memoryAnchor = anchor;
        }
        List<ReturnNode> returnNodes = snippet.getNodes(ReturnNode.TYPE).snapshot();
        if (returnNodes.isEmpty()) {
            this.returnNode = null;
        } else if (returnNodes.size() == 1) {
            this.returnNode = returnNodes.get(0);
        } else {
            AbstractMergeNode merge = snippet.add(new MergeNode());
            List<MemoryMapNode> memMaps = returnNodes.stream().map(n -> n.getMemoryMap()).collect(Collectors.toList());
            ValueNode returnValue = InliningUtil.mergeReturns(merge, returnNodes, null);
            this.returnNode = snippet.add(new ReturnNode(returnValue));
            MemoryMapImpl mmap = FloatingReadPhase.mergeMemoryMaps(merge, memMaps);
            MemoryMapNode memoryMap = snippet.unique(new MemoryMapNode(mmap.getMap()));
            this.returnNode.setMemoryMap(memoryMap);
            for (MemoryMapNode mm : memMaps) {
                if (mm != memoryMap && mm.isAlive()) {
                    assert mm.hasNoUsages();
                    GraphUtil.killWithUnusedFloatingInputs(mm);
                }
            }
            merge.setNext(this.returnNode);
        }

        this.sideEffectNodes = curSideEffectNodes;
        this.deoptNodes = curDeoptNodes;
        this.stampNodes = curStampNodes;

        nodes = new ArrayList<>(snippet.getNodeCount());
        for (Node node : snippet.getNodes()) {
            if (node != entryPointNode && node != entryPointNode.stateAfter()) {
                nodes.add(node);
            }
        }

        Debug.metric("SnippetTemplateNodeCount[%#s]", args).add(nodes.size());
        if (UseSnippetTemplateCache && args.cacheable) {
            args.info.notifyNewTemplate();
        }
        Debug.dump(snippet, "SnippetTemplate final state");
    }

    protected Object[] getConstantArgs(Arguments args) {
        Object[] constantArgs = args.values.clone();
        for (int i = 0; i < args.info.getParameterCount(); i++) {
            if (!args.info.isConstantParameter(i)) {
                constantArgs[i] = null;
            } else {
                assert constantArgs[i] != null : "Can't pass raw null through as argument";
            }
        }
        return constantArgs;
    }

    private static boolean checkAllVarargPlaceholdersAreDeleted(int parameterCount, VarargsPlaceholderNode[] placeholders) {
        for (int i = 0; i < parameterCount; i++) {
            if (placeholders[i] != null) {
                assert placeholders[i].isDeleted() : placeholders[i];
            }
        }
        return true;
    }

    private static boolean checkConstantArgument(MetaAccessProvider metaAccess, final ResolvedJavaMethod method, Signature signature, int i, String name, Object arg, Kind kind) {
        ResolvedJavaType type = signature.getParameterType(i, method.getDeclaringClass()).resolve(method.getDeclaringClass());
        if (metaAccess.lookupJavaType(WordBase.class).isAssignableFrom(type)) {
            assert arg instanceof JavaConstant : method + ": word constant parameters must be passed boxed in a Constant value: " + arg;
            return true;
        }
        if (kind != Kind.Object) {
            assert arg != null && kind.toBoxedJavaClass() == arg.getClass() : method + ": wrong value kind for " + name + ": expected " + kind + ", got " +
                            (arg == null ? "null" : arg.getClass().getSimpleName());
        }
        return true;
    }

    private static boolean checkVarargs(MetaAccessProvider metaAccess, final ResolvedJavaMethod method, Signature signature, int i, String name, Varargs varargs) {
        ResolvedJavaType type = (ResolvedJavaType) signature.getParameterType(i, method.getDeclaringClass());
        assert type.isArray() : "varargs parameter must be an array type";
        assert type.getComponentType().isAssignableFrom(metaAccess.lookupJavaType(varargs.componentType)) : "componentType for " + name + " not matching " + type.toJavaName() + " instance: " +
                        varargs.componentType;
        return true;
    }

    /**
     * The graph built from the snippet method.
     */
    private final StructuredGraph snippet;

    private final SnippetInfo info;

    /**
     * The named parameters of this template that must be bound to values during instantiation. For
     * a parameter that is still live after specialization, the value in this map is either a
     * {@link ParameterNode} instance or a {@link ParameterNode} array. For an eliminated parameter,
     * the value is identical to the key.
     */
    private final Object[] parameters;

    /**
     * The return node (if any) of the snippet.
     */
    private final ReturnNode returnNode;

    /**
     * The memory anchor (if any) of the snippet.
     */
    private final MemoryAnchorNode memoryAnchor;

    /**
     * Nodes that inherit the {@link StateSplit#stateAfter()} from the replacee during
     * instantiation.
     */
    private final ArrayList<StateSplit> sideEffectNodes;

    /**
     * Nodes that inherit a deoptimization {@link FrameState} from the replacee during
     * instantiation.
     */
    private final ArrayList<DeoptimizingNode> deoptNodes;

    /**
     * The nodes that inherit the {@link ValueNode#stamp()} from the replacee during instantiation.
     */
    private final ArrayList<ValueNode> stampNodes;

    /**
     * The nodes to be inlined when this specialization is instantiated.
     */
    private final ArrayList<Node> nodes;

    /**
     * Times instantiations of this template.
     *
     * @see SnippetInfo#instantiationTimer
     */
    private final DebugTimer instantiationTimer;

    /**
     * Counts instantiations of this template.
     *
     * @see SnippetInfo#instantiationCounter
     */
    private final DebugMetric instantiationCounter;

    /**
     * Gets the instantiation-time bindings to this template's parameters.
     *
     * @return the map that will be used to bind arguments to parameters when inlining this template
     */
    private Map<Node, Node> bind(StructuredGraph replaceeGraph, MetaAccessProvider metaAccess, Arguments args) {
        Map<Node, Node> replacements = Node.newIdentityMap();
        assert args.info.getParameterCount() == parameters.length : "number of args (" + args.info.getParameterCount() + ") != number of parameters (" + parameters.length + ")";
        for (int i = 0; i < parameters.length; i++) {
            Object parameter = parameters[i];
            assert parameter != null : this + " has no parameter named " + args.info.getParameterName(i);
            Object argument = args.values[i];
            if (parameter instanceof ParameterNode) {
                if (argument instanceof ValueNode) {
                    replacements.put((ParameterNode) parameter, (ValueNode) argument);
                } else {
                    Kind kind = ((ParameterNode) parameter).getKind();
                    assert argument != null || kind == Kind.Object : this + " cannot accept null for non-object parameter named " + args.info.getParameterName(i);
                    JavaConstant constant = forBoxed(argument, kind);
                    replacements.put((ParameterNode) parameter, ConstantNode.forConstant(constant, metaAccess, replaceeGraph));
                }
            } else if (parameter instanceof ParameterNode[]) {
                ParameterNode[] params = (ParameterNode[]) parameter;
                Varargs varargs = (Varargs) argument;
                int length = params.length;
                List<?> list = null;
                Object array = null;
                if (varargs.value instanceof List) {
                    list = (List<?>) varargs.value;
                    assert list.size() == length : length + " != " + list.size();
                } else {
                    array = varargs.value;
                    assert array != null && array.getClass().isArray();
                    assert Array.getLength(array) == length : length + " != " + Array.getLength(array);
                }

                for (int j = 0; j < length; j++) {
                    ParameterNode param = params[j];
                    assert param != null;
                    Object value = list != null ? list.get(j) : Array.get(array, j);
                    if (value instanceof ValueNode) {
                        replacements.put(param, (ValueNode) value);
                    } else {
                        JavaConstant constant = forBoxed(value, param.getKind());
                        ConstantNode element = ConstantNode.forConstant(constant, metaAccess, replaceeGraph);
                        replacements.put(param, element);
                    }
                }
            } else {
                assert parameter.equals(CONSTANT_PARAMETER) || parameter.equals(UNUSED_PARAMETER) : "unexpected entry for parameter: " + args.info.getParameterName(i) + " -> " + parameter;
            }
        }
        return replacements;
    }

    /**
     * Converts a Java boxed value to a {@link JavaConstant} of the right kind. This adjusts for the
     * limitation that a {@link Local}'s kind is a {@linkplain Kind#getStackKind() stack kind} and
     * so cannot be used for re-boxing primitives smaller than an int.
     *
     * @param argument a Java boxed value
     * @param localKind the kind of the {@link Local} to which {@code argument} will be bound
     */
    protected JavaConstant forBoxed(Object argument, Kind localKind) {
        assert localKind == localKind.getStackKind();
        if (localKind == Kind.Int) {
            return JavaConstant.forBoxedPrimitive(argument);
        }
        return snippetReflection.forBoxed(localKind, argument);
    }

    /**
     * Logic for replacing a snippet-lowered node at its usages with the return value of the
     * snippet. An alternative to the {@linkplain SnippetTemplate#DEFAULT_REPLACER default}
     * replacement logic can be used to handle mismatches between the stamp of the node being
     * lowered and the stamp of the snippet's return value.
     */
    public interface UsageReplacer {
        /**
         * Replaces all usages of {@code oldNode} with direct or indirect usages of {@code newNode}.
         */
        void replace(ValueNode oldNode, ValueNode newNode);
    }

    /**
     * Represents the default {@link UsageReplacer usage replacer} logic which simply delegates to
     * {@link Node#replaceAtUsages(Node)}.
     */
    public static final UsageReplacer DEFAULT_REPLACER = new UsageReplacer() {

        @Override
        public void replace(ValueNode oldNode, ValueNode newNode) {
            if (newNode == null) {
                assert oldNode.hasNoUsages();
            } else {
                oldNode.replaceAtUsages(newNode);
            }
        }
    };

    private boolean assertSnippetKills(ValueNode replacee) {
        if (!replacee.graph().isAfterFloatingReadPhase()) {
            // no floating reads yet, ignore locations created while lowering
            return true;
        }
        if (returnNode == null) {
            // The snippet terminates control flow
            return true;
        }
        MemoryMapNode memoryMap = returnNode.getMemoryMap();
        if (memoryMap == null || memoryMap.isEmpty()) {
            // there are no kills in the snippet graph
            return true;
        }

        Set<LocationIdentity> kills = new HashSet<>(memoryMap.getLocations());

        if (replacee instanceof MemoryCheckpoint.Single) {
            // check if some node in snippet graph also kills the same location
            LocationIdentity locationIdentity = ((MemoryCheckpoint.Single) replacee).getLocationIdentity();
            if (locationIdentity.isAny()) {
                assert !(memoryMap.getLastLocationAccess(any()) instanceof MemoryAnchorNode) : replacee + " kills ANY_LOCATION, but snippet does not";
                // if the replacee kills ANY_LOCATION, the snippet can kill arbitrary locations
                return true;
            }
            assert kills.contains(locationIdentity) : replacee + " kills " + locationIdentity + ", but snippet doesn't contain a kill to this location";
            kills.remove(locationIdentity);
        }
        assert !(replacee instanceof MemoryCheckpoint.Multi) : replacee + " multi not supported (yet)";

        // remove ANY_LOCATION if it's just a kill by the start node
        if (memoryMap.getLastLocationAccess(any()) instanceof MemoryAnchorNode) {
            kills.remove(any());
        }

        // node can only lower to a ANY_LOCATION kill if the replacee also kills ANY_LOCATION
        assert !kills.contains(any()) : "snippet graph contains a kill to ANY_LOCATION, but replacee (" + replacee + ") doesn't kill ANY_LOCATION.  kills: " + kills;

        /*
         * Kills to private locations are safe, since there can be no floating read to these
         * locations except reads that are introduced by the snippet itself or related snippets in
         * the same lowering round. These reads are anchored to a MemoryAnchor at the beginning of
         * their snippet, so they can not float above a kill in another instance of the same
         * snippet.
         */
        for (LocationIdentity p : this.info.privateLocations) {
            kills.remove(p);
        }

        assert kills.isEmpty() : "snippet graph kills non-private locations " + Arrays.toString(kills.toArray()) + " that replacee (" + replacee + ") doesn't kill";
        return true;
    }

    private static class MemoryInputMap implements MemoryMap {

        private final LocationIdentity locationIdentity;
        private final MemoryNode lastLocationAccess;

        public MemoryInputMap(ValueNode replacee) {
            if (replacee instanceof MemoryAccess) {
                MemoryAccess access = (MemoryAccess) replacee;
                locationIdentity = access.getLocationIdentity();
                lastLocationAccess = access.getLastLocationAccess();
            } else {
                locationIdentity = null;
                lastLocationAccess = null;
            }
        }

        @Override
        public MemoryNode getLastLocationAccess(LocationIdentity location) {
            if (locationIdentity != null && locationIdentity.equals(location)) {
                return lastLocationAccess;
            } else {
                return null;
            }
        }

        @Override
        public Collection<LocationIdentity> getLocations() {
            if (locationIdentity == null) {
                return Collections.emptySet();
            } else {
                return Collections.singleton(locationIdentity);
            }
        }
    }

    private class MemoryOutputMap extends MemoryInputMap {

        private final Map<Node, Node> duplicates;

        public MemoryOutputMap(ValueNode replacee, Map<Node, Node> duplicates) {
            super(replacee);
            this.duplicates = duplicates;
        }

        @Override
        public MemoryNode getLastLocationAccess(LocationIdentity locationIdentity) {
            MemoryMapNode memoryMap = returnNode.getMemoryMap();
            assert memoryMap != null : "no memory map stored for this snippet graph (snippet doesn't have a ReturnNode?)";
            MemoryNode lastLocationAccess = memoryMap.getLastLocationAccess(locationIdentity);
            assert lastLocationAccess != null : locationIdentity;
            if (lastLocationAccess == memoryAnchor) {
                return super.getLastLocationAccess(locationIdentity);
            } else {
                return (MemoryNode) duplicates.get(ValueNodeUtil.asNode(lastLocationAccess));
            }
        }

        @Override
        public Collection<LocationIdentity> getLocations() {
            return returnNode.getMemoryMap().getLocations();
        }
    }

    private void rewireMemoryGraph(ValueNode replacee, Map<Node, Node> duplicates) {
        if (replacee.graph().isAfterFloatingReadPhase()) {
            // rewire outgoing memory edges
            replaceMemoryUsages(replacee, new MemoryOutputMap(replacee, duplicates));

            if (returnNode != null) {
                ReturnNode ret = (ReturnNode) duplicates.get(returnNode);
                MemoryMapNode memoryMap = ret.getMemoryMap();
                ret.setMemoryMap(null);
                memoryMap.safeDelete();
            }
            if (memoryAnchor != null) {
                // rewire incoming memory edges
                MemoryAnchorNode memoryDuplicate = (MemoryAnchorNode) duplicates.get(memoryAnchor);
                replaceMemoryUsages(memoryDuplicate, new MemoryInputMap(replacee));

                if (memoryDuplicate.hasNoUsages()) {
                    memoryDuplicate.graph().removeFixed(memoryDuplicate);
                }
            }
        }
    }

    private static LocationIdentity getLocationIdentity(Node node) {
        if (node instanceof MemoryAccess) {
            return ((MemoryAccess) node).getLocationIdentity();
        } else if (node instanceof MemoryProxy) {
            return ((MemoryProxy) node).getLocationIdentity();
        } else if (node instanceof MemoryPhiNode) {
            return ((MemoryPhiNode) node).getLocationIdentity();
        } else {
            return null;
        }
    }

    private void replaceMemoryUsages(ValueNode node, MemoryMap map) {
        for (Node usage : node.usages().snapshot()) {
            if (usage instanceof MemoryMapNode) {
                continue;
            }

            LocationIdentity location = getLocationIdentity(usage);
            if (location != null) {
                NodePosIterator iter = usage.inputs().iterator();
                while (iter.hasNext()) {
                    Position pos = iter.nextPosition();
                    if (pos.getInputType() == InputType.Memory && pos.get(usage) == node) {
                        MemoryNode replacement = map.getLastLocationAccess(location);
                        if (replacement == null) {
                            assert LocationIdentity.any().equals(location) || Arrays.stream(info.privateLocations).anyMatch(Predicate.isEqual(location)) : "Snippet " + info.method.format("%h.%n") +
                                            " contains access to the non-private location " + location + ", but replacee doesn't access this location." + map.getLocations();
                        } else {
                            pos.set(usage, replacement.asNode());
                        }
                    }
                }
            }
        }
    }

    /**
     * Replaces a given fixed node with this specialized snippet.
     *
     * @param metaAccess
     * @param replacee the node that will be replaced
     * @param replacer object that replaces the usages of {@code replacee}
     * @param args the arguments to be bound to the flattened positional parameters of the snippet
     * @return the map of duplicated nodes (original -&gt; duplicate)
     */
    public Map<Node, Node> instantiate(MetaAccessProvider metaAccess, FixedNode replacee, UsageReplacer replacer, Arguments args) {
        assert assertSnippetKills(replacee);
        try (DebugCloseable a = args.info.instantiationTimer.start(); DebugCloseable b = instantiationTimer.start()) {
            args.info.instantiationCounter.increment();
            instantiationCounter.increment();
            // Inline the snippet nodes, replacing parameters with the given args in the process
            StartNode entryPointNode = snippet.start();
            FixedNode firstCFGNode = entryPointNode.next();
            StructuredGraph replaceeGraph = replacee.graph();
            Map<Node, Node> replacements = bind(replaceeGraph, metaAccess, args);
            replacements.put(entryPointNode, AbstractBeginNode.prevBegin(replacee));
            Map<Node, Node> duplicates = replaceeGraph.addDuplicates(nodes, snippet, snippet.getNodeCount(), replacements);
            Debug.dump(replaceeGraph, "After inlining snippet %s", snippet.method());

            // Re-wire the control flow graph around the replacee
            FixedNode firstCFGNodeDuplicate = (FixedNode) duplicates.get(firstCFGNode);
            replacee.replaceAtPredecessor(firstCFGNodeDuplicate);

            if (replacee instanceof StateSplit) {
                for (StateSplit sideEffectNode : sideEffectNodes) {
                    assert ((StateSplit) replacee).hasSideEffect();
                    Node sideEffectDup = duplicates.get(sideEffectNode);
                    ((StateSplit) sideEffectDup).setStateAfter(((StateSplit) replacee).stateAfter());
                }
            }

            if (replacee instanceof DeoptimizingNode) {
                DeoptimizingNode replaceeDeopt = (DeoptimizingNode) replacee;

                FrameState stateBefore = null;
                FrameState stateDuring = null;
                FrameState stateAfter = null;
                if (replaceeDeopt.canDeoptimize()) {
                    if (replaceeDeopt instanceof DeoptimizingNode.DeoptBefore) {
                        stateBefore = ((DeoptimizingNode.DeoptBefore) replaceeDeopt).stateBefore();
                    }
                    if (replaceeDeopt instanceof DeoptimizingNode.DeoptDuring) {
                        stateDuring = ((DeoptimizingNode.DeoptDuring) replaceeDeopt).stateDuring();
                    }
                    if (replaceeDeopt instanceof DeoptimizingNode.DeoptAfter) {
                        stateAfter = ((DeoptimizingNode.DeoptAfter) replaceeDeopt).stateAfter();
                    }
                }

                for (DeoptimizingNode deoptNode : deoptNodes) {
                    DeoptimizingNode deoptDup = (DeoptimizingNode) duplicates.get(deoptNode);
                    if (deoptDup.canDeoptimize()) {
                        if (deoptDup instanceof DeoptimizingNode.DeoptBefore) {
                            ((DeoptimizingNode.DeoptBefore) deoptDup).setStateBefore(stateBefore);
                        }
                        if (deoptDup instanceof DeoptimizingNode.DeoptDuring) {
                            DeoptimizingNode.DeoptDuring deoptDupDuring = (DeoptimizingNode.DeoptDuring) deoptDup;
                            if (stateDuring != null) {
                                deoptDupDuring.setStateDuring(stateDuring);
                            } else if (stateAfter != null) {
                                deoptDupDuring.computeStateDuring(stateAfter);
                            } else if (stateBefore != null) {
                                assert !deoptDupDuring.hasSideEffect() : "can't use stateBefore as stateDuring for state split " + deoptDupDuring;
                                deoptDupDuring.setStateDuring(stateBefore);
                            }
                        }
                        if (deoptDup instanceof DeoptimizingNode.DeoptAfter) {
                            DeoptimizingNode.DeoptAfter deoptDupAfter = (DeoptimizingNode.DeoptAfter) deoptDup;
                            if (stateAfter != null) {
                                deoptDupAfter.setStateAfter(stateAfter);
                            } else {
                                assert !deoptDupAfter.hasSideEffect() : "can't use stateBefore as stateAfter for state split " + deoptDupAfter;
                                deoptDupAfter.setStateAfter(stateBefore);
                            }

                        }
                    }
                }
            }

            updateStamps(replacee, duplicates);

            rewireMemoryGraph(replacee, duplicates);

            // Replace all usages of the replacee with the value returned by the snippet
            ValueNode returnValue = null;
            if (returnNode != null && !(replacee instanceof ControlSinkNode)) {
                ReturnNode returnDuplicate = (ReturnNode) duplicates.get(returnNode);
                returnValue = returnDuplicate.result();
                if (returnValue == null && replacee.usages().isNotEmpty() && replacee instanceof MemoryCheckpoint) {
                    replacer.replace(replacee, null);
                } else {
                    assert returnValue != null || replacee.hasNoUsages();
                    replacer.replace(replacee, returnValue);
                }
                if (returnDuplicate.isAlive()) {
                    FixedNode next = null;
                    if (replacee instanceof FixedWithNextNode) {
                        FixedWithNextNode fwn = (FixedWithNextNode) replacee;
                        next = fwn.next();
                        fwn.setNext(null);
                    }
                    returnDuplicate.replaceAndDelete(next);
                }
            }

            // Remove the replacee from its graph
            GraphUtil.killCFG(replacee);

            Debug.dump(replaceeGraph, "After lowering %s with %s", replacee, this);
            return duplicates;
        }
    }

    private void propagateStamp(Node node) {
        if (node instanceof PhiNode) {
            PhiNode phi = (PhiNode) node;
            if (phi.inferStamp()) {
                for (Node usage : node.usages()) {
                    propagateStamp(usage);
                }
            }
        }
    }

    private void updateStamps(ValueNode replacee, Map<Node, Node> duplicates) {
        for (ValueNode stampNode : stampNodes) {
            Node stampDup = duplicates.get(stampNode);
            ((ValueNode) stampDup).setStamp(replacee.stamp());
        }
        for (ParameterNode paramNode : snippet.getNodes(ParameterNode.TYPE)) {
            for (Node usage : paramNode.usages()) {
                Node usageDup = duplicates.get(usage);
                propagateStamp(usageDup);
            }
        }
    }

    /**
     * Gets a copy of the specialized graph.
     */
    public StructuredGraph copySpecializedGraph() {
        return (StructuredGraph) snippet.copy();
    }

    /**
     * Replaces a given floating node with this specialized snippet.
     *
     * @param metaAccess
     * @param replacee the node that will be replaced
     * @param replacer object that replaces the usages of {@code replacee}
     * @param args the arguments to be bound to the flattened positional parameters of the snippet
     */
    public void instantiate(MetaAccessProvider metaAccess, FloatingNode replacee, UsageReplacer replacer, LoweringTool tool, Arguments args) {
        assert assertSnippetKills(replacee);
        try (DebugCloseable a = args.info.instantiationTimer.start()) {
            args.info.instantiationCounter.increment();
            instantiationCounter.increment();

            // Inline the snippet nodes, replacing parameters with the given args in the process
            String name = snippet.name == null ? "{copy}" : snippet.name + "{copy}";
            StructuredGraph snippetCopy = new StructuredGraph(name, snippet.method(), AllowAssumptions.NO);
            StartNode entryPointNode = snippet.start();
            FixedNode firstCFGNode = entryPointNode.next();
            StructuredGraph replaceeGraph = replacee.graph();
            Map<Node, Node> replacements = bind(replaceeGraph, metaAccess, args);
            replacements.put(entryPointNode, tool.getCurrentGuardAnchor().asNode());
            Map<Node, Node> duplicates = replaceeGraph.addDuplicates(nodes, snippet, snippet.getNodeCount(), replacements);
            Debug.dump(replaceeGraph, "After inlining snippet %s", snippetCopy.method());

            FixedWithNextNode lastFixedNode = tool.lastFixedNode();
            assert lastFixedNode != null && lastFixedNode.isAlive() : replaceeGraph + " lastFixed=" + lastFixedNode;
            FixedNode next = lastFixedNode.next();
            lastFixedNode.setNext(null);
            FixedNode firstCFGNodeDuplicate = (FixedNode) duplicates.get(firstCFGNode);
            replaceeGraph.addAfterFixed(lastFixedNode, firstCFGNodeDuplicate);

            if (replacee instanceof StateSplit) {
                for (StateSplit sideEffectNode : sideEffectNodes) {
                    assert ((StateSplit) replacee).hasSideEffect();
                    Node sideEffectDup = duplicates.get(sideEffectNode);
                    ((StateSplit) sideEffectDup).setStateAfter(((StateSplit) replacee).stateAfter());
                }
            }
            updateStamps(replacee, duplicates);

            rewireMemoryGraph(replacee, duplicates);

            // Replace all usages of the replacee with the value returned by the snippet
            ReturnNode returnDuplicate = (ReturnNode) duplicates.get(returnNode);
            ValueNode returnValue = returnDuplicate.result();
            assert returnValue != null || replacee.hasNoUsages();
            replacer.replace(replacee, returnValue);

            if (returnDuplicate.isAlive()) {
                returnDuplicate.replaceAndDelete(next);
            }

            Debug.dump(replaceeGraph, "After lowering %s with %s", replacee, this);
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(snippet.toString()).append('(');
        String sep = "";
        for (int i = 0; i < parameters.length; i++) {
            String name = "[" + i + "]";
            Object value = parameters[i];
            buf.append(sep);
            sep = ", ";
            if (value == null) {
                buf.append("<null> ").append(name);
            } else if (value.equals(UNUSED_PARAMETER)) {
                buf.append("<unused> ").append(name);
            } else if (value.equals(CONSTANT_PARAMETER)) {
                buf.append("<constant> ").append(name);
            } else if (value instanceof ParameterNode) {
                ParameterNode param = (ParameterNode) value;
                buf.append(param.getKind().getJavaName()).append(' ').append(name);
            } else {
                ParameterNode[] params = (ParameterNode[]) value;
                String kind = params.length == 0 ? "?" : params[0].getKind().getJavaName();
                buf.append(kind).append('[').append(params.length).append("] ").append(name);
            }
        }
        return buf.append(')').toString();
    }

    private static boolean checkTemplate(MetaAccessProvider metaAccess, Arguments args, ResolvedJavaMethod method, Signature signature) {
        for (int i = 0; i < args.info.getParameterCount(); i++) {
            if (args.info.isConstantParameter(i)) {
                Kind kind = signature.getParameterKind(i);
                assert checkConstantArgument(metaAccess, method, signature, i, args.info.getParameterName(i), args.values[i], kind);

            } else if (args.info.isVarargsParameter(i)) {
                assert args.values[i] instanceof Varargs;
                Varargs varargs = (Varargs) args.values[i];
                assert checkVarargs(metaAccess, method, signature, i, args.info.getParameterName(i), varargs);
            }
        }
        return true;
    }
}
