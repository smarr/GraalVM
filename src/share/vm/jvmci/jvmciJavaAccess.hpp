/*
 * Copyright (c) 2011, 2015, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_VM_JVMCI_JVMCI_JAVA_ACCESS_HPP
#define SHARE_VM_JVMCI_JVMCI_JAVA_ACCESS_HPP

void jvmci_compute_offsets();

#include "classfile/systemDictionary.hpp"
#include "oops/instanceMirrorKlass.hpp"

/* This macro defines the structure of the CompilationResult - classes.
 * It will generate classes with accessors similar to javaClasses.hpp, but with specializations for oops, Handles and jni handles.
 *
 * The public interface of these classes will look like this:

 * class StackSlot : AllStatic {
 * public:
 *   static Klass* klass();
 *   static jint  index(oop obj);
 *   static jint  index(Handle obj);
 *   static jint  index(jobject obj);
 *   static void set_index(oop obj, jint x);
 *   static void set_index(Handle obj, jint x);
 *   static void set_index(jobject obj, jint x);
 * };
 *
 */

#define COMPILER_CLASSES_DO(start_class, end_class, char_field, int_field, boolean_field, long_field, float_field, oop_field, typeArrayOop_field, objArrayOop_field, static_oop_field, static_int_field, static_boolean_field) \
  start_class(HotSpotResolvedObjectTypeImpl)                                                                                                                   \
    oop_field(HotSpotResolvedObjectTypeImpl, javaClass, "Ljava/lang/Class;")                                                                                   \
  end_class                                                                                                                                                    \
  start_class(HotSpotResolvedJavaMethodImpl)                                                                                                                   \
    long_field(HotSpotResolvedJavaMethodImpl, metaspaceMethod)                                                                                                 \
  end_class                                                                                                                                                    \
  start_class(InstalledCode)                                                                                                                                   \
    long_field(InstalledCode, address)                                                                                                                         \
    long_field(InstalledCode, version)                                                                                                                         \
    oop_field(InstalledCode, name, "Ljava/lang/String;")                                                                                                       \
  end_class                                                                                                                                                    \
  start_class(HotSpotInstalledCode)                                                                                                                            \
    int_field(HotSpotInstalledCode, size)                                                                                                                      \
    long_field(HotSpotInstalledCode, codeStart)                                                                                                                \
    int_field(HotSpotInstalledCode, codeSize)                                                                                                                  \
  end_class                                                                                                                                                    \
  start_class(HotSpotNmethod)                                                                                                                                  \
    boolean_field(HotSpotNmethod, isDefault)                                                                                                                   \
  end_class                                                                                                                                                    \
  start_class(HotSpotCompiledCode)                                                                                                                             \
    oop_field(HotSpotCompiledCode, name, "Ljava/lang/String;")                                                                                                 \
    objArrayOop_field(HotSpotCompiledCode, sites, "[Ljdk/internal/jvmci/code/CompilationResult$Site;")                                                           \
    objArrayOop_field(HotSpotCompiledCode, exceptionHandlers, "[Ljdk/internal/jvmci/code/CompilationResult$ExceptionHandler;")                                   \
    objArrayOop_field(HotSpotCompiledCode, comments, "[Ljdk/internal/jvmci/hotspot/HotSpotCompiledCode$Comment;")                                                \
    objArrayOop_field(HotSpotCompiledCode, assumptions, "[Ljdk/internal/jvmci/meta/Assumptions$Assumption;")                                                     \
    typeArrayOop_field(HotSpotCompiledCode, targetCode, "[B")                                                                                                  \
    int_field(HotSpotCompiledCode, targetCodeSize)                                                                                                             \
    typeArrayOop_field(HotSpotCompiledCode, dataSection, "[B")                                                                                                 \
    int_field(HotSpotCompiledCode, dataSectionAlignment)                                                                                                       \
    objArrayOop_field(HotSpotCompiledCode, dataSectionPatches, "[Ljdk/internal/jvmci/code/CompilationResult$DataPatch;")                                         \
    int_field(HotSpotCompiledCode, totalFrameSize)                                                                                                             \
    int_field(HotSpotCompiledCode, customStackAreaOffset)                                                                                                      \
    objArrayOop_field(HotSpotCompiledCode, methods, "[Ljdk/internal/jvmci/meta/ResolvedJavaMethod;")                                                             \
  end_class                                                                                                                                                    \
  start_class(HotSpotCompiledCode_Comment)                                                                                                                     \
    oop_field(HotSpotCompiledCode_Comment, text, "Ljava/lang/String;")                                                                                         \
    int_field(HotSpotCompiledCode_Comment, pcOffset)                                                                                                           \
  end_class                                                                                                                                                    \
  start_class(HotSpotCompiledNmethod)                                                                                                                          \
    oop_field(HotSpotCompiledNmethod, method, "Ljdk/internal/jvmci/hotspot/HotSpotResolvedJavaMethod;")                                                          \
    oop_field(HotSpotCompiledNmethod, installationFailureMessage, "Ljava/lang/String;")                                                                        \
    int_field(HotSpotCompiledNmethod, entryBCI)                                                                                                                \
    int_field(HotSpotCompiledNmethod, id)                                                                                                                      \
    long_field(HotSpotCompiledNmethod, jvmciEnv)                                                                                                               \
    boolean_field(HotSpotCompiledNmethod, hasUnsafeAccess)                                                                                                     \
  end_class                                                                                                                                                    \
  start_class(HotSpotForeignCallTarget)                                                                                                                        \
    long_field(HotSpotForeignCallTarget, address)                                                                                                              \
  end_class                                                                                                                                                    \
  start_class(Assumptions_NoFinalizableSubclass)                                                                                                               \
    oop_field(Assumptions_NoFinalizableSubclass, receiverType, "Ljdk/internal/jvmci/meta/ResolvedJavaType;")                                                     \
  end_class                                                                                                                                                    \
  start_class(Assumptions_ConcreteSubtype)                                                                                                                     \
    oop_field(Assumptions_ConcreteSubtype, context, "Ljdk/internal/jvmci/meta/ResolvedJavaType;")                                                                \
    oop_field(Assumptions_ConcreteSubtype, subtype, "Ljdk/internal/jvmci/meta/ResolvedJavaType;")                                                                \
  end_class                                                                                                                                                    \
  start_class(Assumptions_LeafType)                                                                                                                            \
    oop_field(Assumptions_LeafType, context, "Ljdk/internal/jvmci/meta/ResolvedJavaType;")                                                                       \
  end_class                                                                                                                                                    \
  start_class(Assumptions_ConcreteMethod)                                                                                                                      \
    oop_field(Assumptions_ConcreteMethod, method, "Ljdk/internal/jvmci/meta/ResolvedJavaMethod;")                                                                \
    oop_field(Assumptions_ConcreteMethod, context, "Ljdk/internal/jvmci/meta/ResolvedJavaType;")                                                                 \
    oop_field(Assumptions_ConcreteMethod, impl, "Ljdk/internal/jvmci/meta/ResolvedJavaMethod;")                                                                  \
  end_class                                                                                                                                                    \
  start_class(Assumptions_CallSiteTargetValue)                                                                                                                 \
    oop_field(Assumptions_CallSiteTargetValue, callSite, "Ljava/lang/invoke/CallSite;")                                                                        \
    oop_field(Assumptions_CallSiteTargetValue, methodHandle, "Ljava/lang/invoke/MethodHandle;")                                                                \
  end_class                                                                                                                                                    \
  start_class(CompilationResult_Site)                                                                                                                          \
    int_field(CompilationResult_Site, pcOffset)                                                                                                                \
  end_class                                                                                                                                                    \
  start_class(CompilationResult_Call)                                                                                                                          \
    oop_field(CompilationResult_Call, target, "Ljdk/internal/jvmci/meta/InvokeTarget;")                                                                          \
    oop_field(CompilationResult_Call, debugInfo, "Ljdk/internal/jvmci/code/DebugInfo;")                                                                          \
  end_class                                                                                                                                                    \
  start_class(CompilationResult_DataPatch)                                                                                                                     \
    oop_field(CompilationResult_DataPatch, reference, "Ljdk/internal/jvmci/code/CompilationResult$Reference;")                                                   \
  end_class                                                                                                                                                    \
  start_class(CompilationResult_ConstantReference)                                                                                                             \
    oop_field(CompilationResult_ConstantReference, constant, "Ljdk/internal/jvmci/meta/VMConstant;")                                                             \
  end_class                                                                                                                                                    \
  start_class(CompilationResult_DataSectionReference)                                                                                                          \
    int_field(CompilationResult_DataSectionReference, offset)                                                                                                  \
  end_class                                                                                                                                                    \
  start_class(InfopointReason)                                                                                                                                 \
    static_oop_field(InfopointReason, UNKNOWN, "Ljdk/internal/jvmci/code/InfopointReason;")                                                                      \
    static_oop_field(InfopointReason, SAFEPOINT, "Ljdk/internal/jvmci/code/InfopointReason;")                                                                    \
    static_oop_field(InfopointReason, CALL, "Ljdk/internal/jvmci/code/InfopointReason;")                                                                         \
    static_oop_field(InfopointReason, IMPLICIT_EXCEPTION, "Ljdk/internal/jvmci/code/InfopointReason;")                                                           \
    static_oop_field(InfopointReason, METHOD_START, "Ljdk/internal/jvmci/code/InfopointReason;")                                                                 \
    static_oop_field(InfopointReason, METHOD_END, "Ljdk/internal/jvmci/code/InfopointReason;")                                                                   \
    static_oop_field(InfopointReason, LINE_NUMBER, "Ljdk/internal/jvmci/code/InfopointReason;")                                                                  \
  end_class                                                                                                                                                    \
  start_class(CompilationResult_Infopoint)                                                                                                                     \
    oop_field(CompilationResult_Infopoint, debugInfo, "Ljdk/internal/jvmci/code/DebugInfo;")                                                                     \
    oop_field(CompilationResult_Infopoint, reason, "Ljdk/internal/jvmci/code/InfopointReason;")                                                                  \
  end_class                                                                                                                                                    \
  start_class(CompilationResult_ExceptionHandler)                                                                                                              \
    int_field(CompilationResult_ExceptionHandler, handlerPos)                                                                                                  \
  end_class                                                                                                                                                    \
  start_class(CompilationResult_Mark)                                                                                                                          \
    oop_field(CompilationResult_Mark, id, "Ljava/lang/Object;")                                                                                                \
  end_class                                                                                                                                                    \
  start_class(DebugInfo)                                                                                                                                       \
    oop_field(DebugInfo, bytecodePosition, "Ljdk/internal/jvmci/code/BytecodePosition;")                                                                         \
    oop_field(DebugInfo, referenceMap, "Ljdk/internal/jvmci/code/ReferenceMap;")                                                                                 \
    oop_field(DebugInfo, calleeSaveInfo, "Ljdk/internal/jvmci/code/RegisterSaveLayout;")                                                                         \
    objArrayOop_field(DebugInfo, virtualObjectMapping, "[Ljdk/internal/jvmci/code/VirtualObject;")                                                               \
  end_class                                                                                                                                                    \
  start_class(HotSpotReferenceMap)                                                                                                                             \
    objArrayOop_field(HotSpotReferenceMap, objects, "[Ljdk/internal/jvmci/code/Location;")                                                                       \
    objArrayOop_field(HotSpotReferenceMap, derivedBase, "[Ljdk/internal/jvmci/code/Location;")                                                                   \
    typeArrayOop_field(HotSpotReferenceMap, sizeInBytes, "[I")                                                                                                 \
    int_field(HotSpotReferenceMap, maxRegisterSize)                                                                                                            \
  end_class                                                                                                                                                    \
  start_class(RegisterSaveLayout)                                                                                                                              \
    objArrayOop_field(RegisterSaveLayout, registers, "[Ljdk/internal/jvmci/code/Register;")                                                                      \
    typeArrayOop_field(RegisterSaveLayout, slots, "[I")                                                                                                        \
  end_class                                                                                                                                                    \
  start_class(BytecodeFrame)                                                                                                                                   \
    objArrayOop_field(BytecodeFrame, values, "[Ljdk/internal/jvmci/meta/Value;")                                                                                 \
    int_field(BytecodeFrame, numLocals)                                                                                                                        \
    int_field(BytecodeFrame, numStack)                                                                                                                         \
    int_field(BytecodeFrame, numLocks)                                                                                                                         \
    boolean_field(BytecodeFrame, rethrowException)                                                                                                             \
    boolean_field(BytecodeFrame, duringCall)                                                                                                                   \
    static_int_field(BytecodeFrame, BEFORE_BCI)                                                                                                                \
  end_class                                                                                                                                                    \
  start_class(BytecodePosition)                                                                                                                                \
    oop_field(BytecodePosition, caller, "Ljdk/internal/jvmci/code/BytecodePosition;")                                                                            \
    oop_field(BytecodePosition, method, "Ljdk/internal/jvmci/meta/ResolvedJavaMethod;")                                                                          \
    int_field(BytecodePosition, bci)                                                                                                                           \
  end_class                                                                                                                                                    \
  start_class(JavaConstant)                                                                                                                                    \
  end_class                                                                                                                                                    \
  start_class(PrimitiveConstant)                                                                                                                               \
    long_field(PrimitiveConstant, primitive)                                                                                                                   \
  end_class                                                                                                                                                    \
  start_class(RawConstant)                                                                                                                                     \
    long_field(RawConstant, primitive)                                                                                                                         \
  end_class                                                                                                                                                    \
  start_class(NullConstant)                                                                                                                                    \
  end_class                                                                                                                                                    \
  start_class(HotSpotCompressedNullConstant)                                                                                                                   \
  end_class                                                                                                                                                    \
  start_class(HotSpotObjectConstantImpl)                                                                                                                       \
    oop_field(HotSpotObjectConstantImpl, object, "Ljava/lang/Object;")                                                                                         \
    boolean_field(HotSpotObjectConstantImpl, compressed)                                                                                                       \
  end_class                                                                                                                                                    \
  start_class(HotSpotMetaspaceConstantImpl)                                                                                                                    \
    long_field(HotSpotMetaspaceConstantImpl, primitive)                                                                                                        \
    oop_field(HotSpotMetaspaceConstantImpl, metaspaceObject, "Ljava/lang/Object;")                                                                             \
    boolean_field(HotSpotMetaspaceConstantImpl, compressed)                                                                                                    \
  end_class                                                                                                                                                    \
  start_class(Kind)                                                                                                                                            \
    char_field(Kind, typeChar)                                                                                                                                 \
    static_oop_field(Kind, Boolean, "Ljdk/internal/jvmci/meta/Kind;");                                                                                           \
    static_oop_field(Kind, Byte, "Ljdk/internal/jvmci/meta/Kind;");                                                                                              \
    static_oop_field(Kind, Char, "Ljdk/internal/jvmci/meta/Kind;");                                                                                              \
    static_oop_field(Kind, Short, "Ljdk/internal/jvmci/meta/Kind;");                                                                                             \
    static_oop_field(Kind, Int, "Ljdk/internal/jvmci/meta/Kind;");                                                                                               \
    static_oop_field(Kind, Long, "Ljdk/internal/jvmci/meta/Kind;");                                                                                              \
  end_class                                                                                                                                                    \
  start_class(LIRKind)                                                                                                                                         \
    oop_field(LIRKind, platformKind, "Ljdk/internal/jvmci/meta/PlatformKind;")                                                                                   \
    int_field(LIRKind, referenceMask)                                                                                                                          \
  end_class                                                                                                                                                    \
  start_class(AbstractValue)                                                                                                                                   \
    oop_field(AbstractValue, kind, "Ljdk/internal/jvmci/meta/Kind;")                                                                                             \
    oop_field(AbstractValue, lirKind, "Ljdk/internal/jvmci/meta/LIRKind;")                                                                                       \
    static_oop_field(AbstractValue, ILLEGAL, "Ljdk/internal/jvmci/meta/AllocatableValue;");                                                                      \
  end_class                                                                                                                                                    \
  start_class(RegisterValue)                                                                                                                                   \
    oop_field(RegisterValue, reg, "Ljdk/internal/jvmci/code/Register;")                                                                                          \
  end_class                                                                                                                                                    \
  start_class(code_Location)                                                                                                                                   \
    oop_field(code_Location, reg, "Ljdk/internal/jvmci/code/Register;")                                                                                          \
    int_field(code_Location, offset)                                                                                                                           \
  end_class                                                                                                                                                    \
  start_class(code_Register)                                                                                                                                   \
    int_field(code_Register, number)                                                                                                                           \
    int_field(code_Register, encoding)                                                                                                                         \
  end_class                                                                                                                                                    \
  start_class(StackSlot)                                                                                                                                       \
    int_field(StackSlot, offset)                                                                                                                               \
    boolean_field(StackSlot, addFrameSize)                                                                                                                     \
  end_class                                                                                                                                                    \
  start_class(VirtualObject)                                                                                                                                   \
    int_field(VirtualObject, id)                                                                                                                               \
    oop_field(VirtualObject, type, "Ljdk/internal/jvmci/meta/ResolvedJavaType;")                                                                                 \
    objArrayOop_field(VirtualObject, values, "[Ljdk/internal/jvmci/meta/Value;")                                                                                 \
  end_class                                                                                                                                                    \
  start_class(StackLockValue)                                                                                                                                  \
    oop_field(StackLockValue, owner, "Ljdk/internal/jvmci/meta/Value;")                                                                                          \
    oop_field(StackLockValue, slot, "Ljdk/internal/jvmci/code/StackSlotValue;")                                                                                  \
    boolean_field(StackLockValue, eliminated)                                                                                                                  \
  end_class                                                                                                                                                    \
  start_class(SpeculationLog)                                                                                                                                  \
    oop_field(SpeculationLog, lastFailed, "Ljava/lang/Object;")                                                                                                \
  end_class                                                                                                                                                    \
  start_class(HotSpotStackFrameReference)                                                                                                                      \
    oop_field(HotSpotStackFrameReference, compilerToVM, "Ljdk/internal/jvmci/hotspot/CompilerToVM;")                                                             \
    long_field(HotSpotStackFrameReference, stackPointer)                                                                                                       \
    int_field(HotSpotStackFrameReference, frameNumber)                                                                                                         \
    int_field(HotSpotStackFrameReference, bci)                                                                                                                 \
    long_field(HotSpotStackFrameReference, metaspaceMethod)                                                                                                    \
    objArrayOop_field(HotSpotStackFrameReference, locals, "[Ljava/lang/Object;")                                                                               \
    typeArrayOop_field(HotSpotStackFrameReference, localIsVirtual, "[Z")                                                                                       \
  end_class                                                                                                                                                    \
  start_class(Debug)                                                                                                                                           \
    static_boolean_field(Debug, ENABLED)                                                                                                                       \
  end_class                                                                                                                                                    \
  /* end*/

#define START_CLASS(name)                                                                                                                                      \
class name : AllStatic {                                                                                                                                       \
  private:                                                                                                                                                     \
    friend class JVMCICompiler;                                                                                                                                \
    static void check(oop obj, const char* field_name) {                                                                                                       \
        assert(obj != NULL, err_msg("NULL field access of %s.%s", #name, field_name));                                                                         \
        assert(obj->is_a(SystemDictionary::name##_klass()), "wrong class, " #name " expected");                                                                \
    }                                                                                                                                                          \
    static void compute_offsets();                                                                                                                             \
  public:                                                                                                                                                      \
    static Klass* klass() { return SystemDictionary::name##_klass(); }

#define END_CLASS };

#define FIELD(name, type, accessor, cast)                                                                                                                         \
    static int _##name##_offset;                                                                                                                                  \
    static type name(oop obj)                   { check(obj, #name); return cast obj->accessor(_##name##_offset); }                                               \
    static type name(Handle& obj)                { check(obj(), #name); return cast obj->accessor(_##name##_offset); }                                            \
    static type name(jobject obj)               { check(JNIHandles::resolve(obj), #name); return cast JNIHandles::resolve(obj)->accessor(_##name##_offset); }     \
    static void set_##name(oop obj, type x)     { check(obj, #name); obj->accessor##_put(_##name##_offset, x); }                                                  \
    static void set_##name(Handle& obj, type x)  { check(obj(), #name); obj->accessor##_put(_##name##_offset, x); }                                               \
    static void set_##name(jobject obj, type x) { check(JNIHandles::resolve(obj), #name); JNIHandles::resolve(obj)->accessor##_put(_##name##_offset, x); }

#define EMPTY_CAST 
#define CHAR_FIELD(klass, name) FIELD(name, jchar, char_field, EMPTY_CAST)
#define INT_FIELD(klass, name) FIELD(name, jint, int_field, EMPTY_CAST)
#define BOOLEAN_FIELD(klass, name) FIELD(name, jboolean, bool_field, EMPTY_CAST)
#define LONG_FIELD(klass, name) FIELD(name, jlong, long_field, EMPTY_CAST)
#define FLOAT_FIELD(klass, name) FIELD(name, jfloat, float_field, EMPTY_CAST)
#define OOP_FIELD(klass, name, signature) FIELD(name, oop, obj_field, EMPTY_CAST)
#define OBJARRAYOOP_FIELD(klass, name, signature) FIELD(name, objArrayOop, obj_field, (objArrayOop))
#define TYPEARRAYOOP_FIELD(klass, name, signature) FIELD(name, typeArrayOop, obj_field, (typeArrayOop))
#define STATIC_OOP_FIELD(klassName, name, signature)                                                           \
    static int _##name##_offset;                                                                               \
    static oop name() {                                                                                        \
      InstanceKlass* ik = InstanceKlass::cast(klassName::klass());                                             \
      address addr = ik->static_field_addr(_##name##_offset - InstanceMirrorKlass::offset_of_static_fields()); \
      if (UseCompressedOops) {                                                                                 \
        return oopDesc::load_decode_heap_oop((narrowOop *)addr);                                               \
      } else {                                                                                                 \
        return oopDesc::load_decode_heap_oop((oop*)addr);                                                      \
      }                                                                                                        \
    }                                                                                                          \
    static void set_##name(oop x) {                                                                            \
      InstanceKlass* ik = InstanceKlass::cast(klassName::klass());                                             \
      address addr = ik->static_field_addr(_##name##_offset - InstanceMirrorKlass::offset_of_static_fields()); \
      if (UseCompressedOops) {                                                                                 \
        oop_store((narrowOop *)addr, x);                                                                       \
      } else {                                                                                                 \
        oop_store((oop*)addr, x);                                                                              \
      }                                                                                                        \
    }
#define STATIC_PRIMITIVE_FIELD(klassName, name, jtypename)                                                     \
    static int _##name##_offset;                                                                               \
    static jtypename name() {                                                                                  \
      InstanceKlass* ik = InstanceKlass::cast(klassName::klass());                                             \
      address addr = ik->static_field_addr(_##name##_offset - InstanceMirrorKlass::offset_of_static_fields()); \
      return *((jtypename *)addr);                                                                             \
    }                                                                                                          \
    static void set_##name(jtypename x) {                                                                      \
      InstanceKlass* ik = InstanceKlass::cast(klassName::klass());                                             \
      address addr = ik->static_field_addr(_##name##_offset - InstanceMirrorKlass::offset_of_static_fields()); \
      *((jtypename *)addr) = x;                                                                                \
    }

#define STATIC_INT_FIELD(klassName, name) STATIC_PRIMITIVE_FIELD(klassName, name, jint)
#define STATIC_BOOLEAN_FIELD(klassName, name) STATIC_PRIMITIVE_FIELD(klassName, name, jboolean)

COMPILER_CLASSES_DO(START_CLASS, END_CLASS, CHAR_FIELD, INT_FIELD, BOOLEAN_FIELD, LONG_FIELD, FLOAT_FIELD, OOP_FIELD, TYPEARRAYOOP_FIELD, OBJARRAYOOP_FIELD, STATIC_OOP_FIELD, STATIC_INT_FIELD, STATIC_BOOLEAN_FIELD)
#undef START_CLASS
#undef END_CLASS
#undef FIELD
#undef CHAR_FIELD
#undef INT_FIELD
#undef BOOLEAN_FIELD
#undef LONG_FIELD
#undef FLOAT_FIELD
#undef OOP_FIELD
#undef TYPEARRAYOOP_FIELD
#undef OBJARRAYOOP_FIELD
#undef STATIC_OOP_FIELD
#undef STATIC_INT_FIELD
#undef STATIC_BOOLEAN_FIELD
#undef EMPTY_CAST

void compute_offset(int &dest_offset, Klass* klass, const char* name, const char* signature, bool static_field);

#endif // SHARE_VM_JVMCI_JVMCI_JAVA_ACCESS_HPP
