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

#include "precompiled.hpp"
#include "runtime/javaCalls.hpp"
#include "graal/graalEnv.hpp"
#include "graal/graalCompiler.hpp"
#include "graal/graalCodeInstaller.hpp"
#include "graal/graalJavaAccess.hpp"
#include "graal/graalCompilerToVM.hpp"
#include "graal/graalVmIds.hpp"
#include "c1/c1_Runtime1.hpp"
#include "classfile/vmSymbols.hpp"
#include "vmreg_x86.inline.hpp"


// TODO this should be handled in a more robust way - not hard coded...
Register CPU_REGS[] = { rax, rcx, rdx, rbx, rsp, rbp, rsi, rdi, r8, r9, r10, r11, r12, r13, r14, r15 };
bool OOP_ALLOWED[] = {true, true, true, true, false, false, true, true, true, true, false, true, true, true, true, true};
const static int NUM_CPU_REGS = sizeof(CPU_REGS) / sizeof(Register);
XMMRegister XMM_REGS[] = { xmm0, xmm1, xmm2, xmm3, xmm4, xmm5, xmm6, xmm7, xmm8, xmm9, xmm10, xmm11, xmm12, xmm13, xmm14, xmm15 };
const static int NUM_XMM_REGS = sizeof(XMM_REGS) / sizeof(XMMRegister);
const static int NUM_REGS = NUM_CPU_REGS + NUM_XMM_REGS;
const static jlong NO_REF_MAP = 0x8000000000000000L;

// convert Graal register indices (as used in oop maps) to HotSpot registers
VMReg get_hotspot_reg(jint graal_reg) {

  assert(graal_reg >= 0 && graal_reg < NUM_REGS, "invalid register number");
  if (graal_reg < NUM_CPU_REGS) {
    return CPU_REGS[graal_reg]->as_VMReg();
  } else {
    return XMM_REGS[graal_reg - NUM_CPU_REGS]->as_VMReg();
  }
}

const int MapWordBits = 64;

static bool is_bit_set(oop bit_map, int i) {
  jint extra_idx = i / MapWordBits;
  arrayOop extra = (arrayOop) GraalBitMap::words(bit_map);
  assert(extra_idx >= 0 && extra_idx < extra->length(), "unexpected index");
  jlong word = ((jlong*) extra->base(T_LONG))[extra_idx];
  return (word & (1LL << (i % MapWordBits))) != 0;
}

static int bitmap_size(oop bit_map) {
  arrayOop arr = (arrayOop) GraalBitMap::words(bit_map);
  return arr->length() * MapWordBits;
}

// creates a HotSpot oop map out of the byte arrays provided by DebugInfo
static OopMap* create_oop_map(jint total_frame_size, jint parameter_count, oop debug_info) {
  OopMap* map = new OopMap(total_frame_size, parameter_count);
  oop register_map = (oop) DebugInfo::registerRefMap(debug_info);
  oop frame_map = (oop) DebugInfo::frameRefMap(debug_info);

  if (register_map != NULL) {
    for (jint i = 0; i < NUM_CPU_REGS; i++) {
      bool is_oop = is_bit_set(register_map, i);
      VMReg reg = get_hotspot_reg(i);
      if (is_oop) {
        assert(OOP_ALLOWED[i], "this register may never be an oop, register map misaligned?");
        map->set_oop(reg);
      } else {
        map->set_value(reg);
      }
    }
  }

  for (jint i = 0; i < bitmap_size(frame_map); i++) {
    bool is_oop = is_bit_set(frame_map, i);
    // HotSpot stack slots are 4 bytes
    VMReg reg = VMRegImpl::stack2reg(i * 2);
    if (is_oop) {
      map->set_oop(reg);
    } else {
      map->set_value(reg);
    }
  }

  return map;
}

// Records any Metadata values embedded in a Constant (e.g., the value returned by HotSpotResolvedObjectType.klass()).
static void record_metadata_in_constant(oop constant, OopRecorder* oop_recorder) {
  char kind = Kind::typeChar(Constant::kind(constant));
  char wordKind = 'j';
  if (kind == wordKind) {
    oop obj = Constant::object(constant);
    jlong prim = Constant::primitive(constant);
    if (obj != NULL) {
      if (obj->is_a(HotSpotResolvedObjectType::klass())) {
        Klass* klass = (Klass*) (address) HotSpotResolvedObjectType::metaspaceKlass(obj);
        assert((Klass*) prim == klass, err_msg("%s @ %p != %p", klass->name()->as_C_string(), klass, prim));
        int index = oop_recorder->find_index(klass);
        TRACE_graal_3("metadata[%d of %d] = %s", index, oop_recorder->metadata_count(), klass->name()->as_C_string());
      } else {
        assert(java_lang_String::is_instance(obj),
            err_msg("unexpected annotation type (%s) for constant %ld (%p) of kind %c", obj->klass()->name()->as_C_string(), prim, prim, kind));
      }
    }
  }
}

static ScopeValue* get_hotspot_value(oop value, int total_frame_size, GrowableArray<ScopeValue*>* objects, ScopeValue* &second, OopRecorder* oop_recorder) {
  second = NULL;
  if (value == Value::ILLEGAL()) {
    return new LocationValue(Location::new_stk_loc(Location::invalid, 0));
  }

  BasicType type = GraalCompiler::kindToBasicType(Kind::typeChar(Value::kind(value)));
  Location::Type locationType = Location::normal;
  if (type == T_OBJECT || type == T_ARRAY) locationType = Location::oop;

  if (value->is_a(RegisterValue::klass())) {
    jint number = code_Register::number(RegisterValue::reg(value));
    if (number < 16) {
      if (type == T_INT || type == T_FLOAT || type == T_SHORT || type == T_CHAR || type == T_BOOLEAN || type == T_BYTE || type == T_ADDRESS) {
        locationType = Location::int_in_long;
      } else if (type == T_LONG) {
        locationType = Location::lng;
      } else {
        assert(type == T_OBJECT || type == T_ARRAY, "unexpected type in cpu register");
      }
      ScopeValue* value = new LocationValue(Location::new_reg_loc(locationType, as_Register(number)->as_VMReg()));
      if (type == T_LONG) {
        second = value;
      }
      return value;
    } else {
      assert(type == T_FLOAT || type == T_DOUBLE, "only float and double expected in xmm register");
      if (type == T_FLOAT) {
        // this seems weird, but the same value is used in c1_LinearScan
        locationType = Location::normal;
      } else {
        locationType = Location::dbl;
      }
      ScopeValue* value = new LocationValue(Location::new_reg_loc(locationType, as_XMMRegister(number - 16)->as_VMReg()));
      if (type == T_DOUBLE) {
        second = value;
      }
      return value;
    }
  } else if (value->is_a(StackSlot::klass())) {
    if (type == T_DOUBLE) {
      locationType = Location::dbl;
    } else if (type == T_LONG) {
      locationType = Location::lng;
    }
    jint offset = StackSlot::offset(value);
    if (StackSlot::addFrameSize(value)) {
      offset += total_frame_size;
    }
    ScopeValue* value = new LocationValue(Location::new_stk_loc(locationType, offset));
    if (type == T_DOUBLE || type == T_LONG) {
      second = value;
    }
    return value;
  } else if (value->is_a(Constant::klass())){
    record_metadata_in_constant(value, oop_recorder);
    jlong prim = Constant::primitive(value);
    if (type == T_INT || type == T_FLOAT || type == T_SHORT || type == T_CHAR || type == T_BOOLEAN || type == T_BYTE) {
      return new ConstantIntValue(*(jint*)&prim);
    } else if (type == T_LONG || type == T_DOUBLE) {
      second = new ConstantIntValue(0);
      return new ConstantLongValue(prim);
    } else if (type == T_OBJECT) {
      oop obj = Constant::object(value);
      if (obj == NULL) {
        return new ConstantOopWriteValue(NULL);
      } else {
        return new ConstantOopWriteValue(JNIHandles::make_local(obj));
      }
    } else if (type == T_ADDRESS) {
      return new ConstantLongValue(prim);
    }
    tty->print("%i", type);
  } else if (value->is_a(VirtualObject::klass())) {
    oop type = VirtualObject::type(value);
    int id = VirtualObject::id(value);
    oop javaMirror = HotSpotResolvedObjectType::javaMirror(type);
    Klass* klass = java_lang_Class::as_Klass(javaMirror);
    bool isLongArray = klass == Universe::longArrayKlassObj();

    for (jint i = 0; i < objects->length(); i++) {
      ObjectValue* obj = (ObjectValue*) objects->at(i);
      if (obj->id() == id) {
        return obj;
      }
    }

    ObjectValue* sv = new ObjectValue(id, new ConstantOopWriteValue(JNIHandles::make_local(Thread::current(), javaMirror)));
    objects->append(sv);

    arrayOop values = (arrayOop) VirtualObject::values(value);
    for (jint i = 0; i < values->length(); i++) {
      ScopeValue* cur_second = NULL;
      ScopeValue* value = get_hotspot_value(((oop*) values->base(T_OBJECT))[i], total_frame_size, objects, cur_second, oop_recorder);
      
      if (isLongArray && cur_second == NULL) {
        // we're trying to put ints into a long array... this isn't really valid, but it's used for some optimizations.
        // add an int 0 constant
#ifdef VM_LITTLE_ENDIAN
        cur_second = new ConstantIntValue(0);
#else
        cur_second = value;
        value = new ConstantIntValue(0);
#endif
      }

      if (cur_second != NULL) {
        sv->field_values()->append(cur_second);
      }
      sv->field_values()->append(value);
    }
    return sv;
  } else {
    value->klass()->print();
    value->print();
  }
  ShouldNotReachHere();
  return NULL;
}

static MonitorValue* get_monitor_value(oop value, int total_frame_size, GrowableArray<ScopeValue*>* objects, OopRecorder* oop_recorder) {
  guarantee(value->is_a(code_MonitorValue::klass()), "Monitors must be of type MonitorValue");

  ScopeValue* second = NULL;
  ScopeValue* owner_value = get_hotspot_value(code_MonitorValue::owner(value), total_frame_size, objects, second, oop_recorder);
  assert(second == NULL, "monitor cannot occupy two stack slots");

  ScopeValue* lock_data_value = get_hotspot_value(code_MonitorValue::lockData(value), total_frame_size, objects, second, oop_recorder);
  assert(second == lock_data_value, "monitor is LONG value that occupies two stack slots");
  assert(lock_data_value->is_location(), "invalid monitor location");
  Location lock_data_loc = ((LocationValue*)lock_data_value)->location();

  bool eliminated = false;
  if (code_MonitorValue::eliminated(value)) {
    eliminated = true;
  }

  return new MonitorValue(owner_value, lock_data_loc, eliminated);
}

void CodeInstaller::initialize_assumptions(oop target_method) {
  _oop_recorder = new OopRecorder(&_arena);
  _dependencies = new Dependencies(&_arena, _oop_recorder);
  Handle assumptions_handle = CompilationResult::assumptions(HotSpotCompilationResult::comp(target_method));
  if (!assumptions_handle.is_null()) {
    objArrayHandle assumptions(Thread::current(), (objArrayOop)Assumptions::list(assumptions_handle()));
    int length = assumptions->length();
    for (int i = 0; i < length; ++i) {
      Handle assumption = assumptions->obj_at(i);
      if (!assumption.is_null()) {
        if (assumption->klass() == Assumptions_MethodContents::klass()) {
          assumption_MethodContents(assumption);
        } else if (assumption->klass() == Assumptions_ConcreteSubtype::klass()) {
          assumption_ConcreteSubtype(assumption);
        } else if (assumption->klass() == Assumptions_ConcreteMethod::klass()) {
          assumption_ConcreteMethod(assumption);
        } else {
          assumption->print();
          fatal("unexpected Assumption subclass");
        }
      }
    }
  }
}

// constructor used to create a method
CodeInstaller::CodeInstaller(Handle& comp_result, methodHandle method, GraalEnv::CodeInstallResult& result, nmethod*& nm, Handle installed_code) {
  GraalCompiler::initialize_buffer_blob();
  CodeBuffer buffer(JavaThread::current()->get_buffer_blob());
  jobject comp_result_obj = JNIHandles::make_local(comp_result());
  jint entry_bci = HotSpotCompilationResult::entryBCI(comp_result);
  initialize_assumptions(JNIHandles::resolve(comp_result_obj));

  {
    No_Safepoint_Verifier no_safepoint;
    initialize_fields(JNIHandles::resolve(comp_result_obj), method);
    initialize_buffer(buffer);
    process_exception_handlers();
  }

  int stack_slots = _total_frame_size / HeapWordSize; // conversion to words

  result = GraalEnv::register_method(method, nm, entry_bci, &_offsets, _custom_stack_area_offset, &buffer, stack_slots, _debug_recorder->_oopmaps, &_exception_handler_table,
    &_implicit_exception_table, GraalCompiler::instance(), _debug_recorder, _dependencies, NULL, -1, true, false, installed_code);

  method->clear_queued_for_compilation();
}

// constructor used to create a stub
CodeInstaller::CodeInstaller(Handle& target_method, BufferBlob*& blob, jlong& id) {
  No_Safepoint_Verifier no_safepoint;
  
  _oop_recorder = new OopRecorder(&_arena);
  initialize_fields(target_method(), NULL);
  assert(_name != NULL, "installMethod needs NON-NULL name");

  // (very) conservative estimate: each site needs a relocation
  GraalCompiler::initialize_buffer_blob();
  CodeBuffer buffer(JavaThread::current()->get_buffer_blob());
  initialize_buffer(buffer);

  const char* cname = java_lang_String::as_utf8_string(_name);
  blob = BufferBlob::create(strdup(cname), &buffer); // this is leaking strings... but only a limited number of stubs will be created
  IF_TRACE_graal_3 Disassembler::decode((CodeBlob*) blob);
  id = VmIds::addStub(blob->code_begin());
}

void CodeInstaller::initialize_fields(oop comp_result, methodHandle method) {
  _comp_result = HotSpotCompilationResult::comp(comp_result);
  if (!method.is_null()) {
    _parameter_count = method->size_of_parameters();
    TRACE_graal_1("installing code for %s", method->name_and_sig_as_C_string());
  }
  _name = HotSpotCompilationResult::name(comp_result);
  _sites = (arrayOop) HotSpotCompilationResult::sites(comp_result);
  _exception_handlers = (arrayOop) HotSpotCompilationResult::exceptionHandlers(comp_result);

  _code = (arrayOop) CompilationResult::targetCode(_comp_result);
  _code_size = CompilationResult::targetCodeSize(_comp_result);
  // The frame size we get from the target method does not include the return address, so add one word for it here.
  _total_frame_size = CompilationResult::frameSize(_comp_result) + HeapWordSize;
  _custom_stack_area_offset = CompilationResult::customStackAreaOffset(_comp_result);

  // (very) conservative estimate: each site needs a constant section entry
  _constants_size = _sites->length() * (BytesPerLong*2);
  _total_size = align_size_up(_code_size, HeapWordSize) + _constants_size;

  _next_call_type = MARK_INVOKE_INVALID;
}

// perform data and call relocation on the CodeBuffer
void CodeInstaller::initialize_buffer(CodeBuffer& buffer) {
  int locs_buffer_size = _sites->length() * (relocInfo::length_limit + sizeof(relocInfo));
  char* locs_buffer = NEW_RESOURCE_ARRAY(char, locs_buffer_size);
  buffer.insts()->initialize_shared_locs((relocInfo*)locs_buffer, locs_buffer_size / sizeof(relocInfo));
  buffer.initialize_stubs_size(256);
  buffer.initialize_consts_size(_constants_size);

  _debug_recorder = new DebugInformationRecorder(_oop_recorder);
  _debug_recorder->set_oopmaps(new OopMapSet());
  
  buffer.initialize_oop_recorder(_oop_recorder);

  _instructions = buffer.insts();
  _constants = buffer.consts();

  // copy the code into the newly created CodeBuffer
  memcpy(_instructions->start(), _code->base(T_BYTE), _code_size);
  _instructions->set_end(_instructions->start() + _code_size);

  oop* sites = (oop*) _sites->base(T_OBJECT);
  for (int i = 0; i < _sites->length(); i++) {
    oop site = sites[i];
    jint pc_offset = CompilationResult_Site::pcOffset(site);

    if (site->is_a(CompilationResult_Call::klass())) {
      TRACE_graal_4("call at %i", pc_offset);
      site_Call(buffer, pc_offset, site);
    } else if (site->is_a(CompilationResult_Safepoint::klass())) {
      TRACE_graal_4("safepoint at %i", pc_offset);
      site_Safepoint(buffer, pc_offset, site);
    } else if (site->is_a(CompilationResult_DataPatch::klass())) {
      TRACE_graal_4("datapatch at %i", pc_offset);
      site_DataPatch(buffer, pc_offset, site);
    } else if (site->is_a(CompilationResult_Mark::klass())) {
      TRACE_graal_4("mark at %i", pc_offset);
      site_Mark(buffer, pc_offset, site);
    } else {
      fatal("unexpected Site subclass");
    }
  }
}

void CodeInstaller::assumption_MethodContents(Handle assumption) {
  Handle method_handle = Assumptions_MethodContents::method(assumption());
  methodHandle method = getMethodFromHotSpotMethod(method_handle());
  _dependencies->assert_evol_method(method());
}

void CodeInstaller::assumption_ConcreteSubtype(Handle assumption) {
  Handle context_handle = Assumptions_ConcreteSubtype::context(assumption());
  Handle subtype_handle = Assumptions_ConcreteSubtype::subtype(assumption());
  Klass* context = asKlass(HotSpotResolvedObjectType::metaspaceKlass(context_handle));
  Klass* subtype = asKlass(HotSpotResolvedObjectType::metaspaceKlass(subtype_handle));

  _dependencies->assert_leaf_type(subtype);
  if (context != subtype) {
    assert(context->is_abstract(), "");
    _dependencies->assert_abstract_with_unique_concrete_subtype(context, subtype);
  }
}

void CodeInstaller::assumption_ConcreteMethod(Handle assumption) {
  Handle impl_handle = Assumptions_ConcreteMethod::impl(assumption());
  Handle context_handle = Assumptions_ConcreteMethod::context(assumption());

  methodHandle impl = getMethodFromHotSpotMethod(impl_handle());
  Klass* context = asKlass(HotSpotResolvedObjectType::metaspaceKlass(context_handle));

  _dependencies->assert_unique_concrete_method(context, impl());
}

void CodeInstaller::process_exception_handlers() {
  // allocate some arrays for use by the collection code.
  const int num_handlers = 5;
  GrowableArray<intptr_t>* bcis = new GrowableArray<intptr_t> (num_handlers);
  GrowableArray<intptr_t>* scope_depths = new GrowableArray<intptr_t> (num_handlers);
  GrowableArray<intptr_t>* pcos = new GrowableArray<intptr_t> (num_handlers);

  if (_exception_handlers != NULL) {
    oop* exception_handlers = (oop*) _exception_handlers->base(T_OBJECT);
    for (int i = 0; i < _exception_handlers->length(); i++) {
      oop exc = exception_handlers[i];
      jint pc_offset = CompilationResult_Site::pcOffset(exc);
      jint handler_offset = CompilationResult_ExceptionHandler::handlerPos(exc);

      // Subtable header
      _exception_handler_table.add_entry(HandlerTableEntry(1, pc_offset, 0));

      // Subtable entry
      _exception_handler_table.add_entry(HandlerTableEntry(-1, handler_offset, 0));
    }
  }
}

void CodeInstaller::record_scope(jint pc_offset, oop frame, GrowableArray<ScopeValue*>* objects) {
  assert(frame->klass() == BytecodeFrame::klass(), "BytecodeFrame expected");
  oop caller_frame = BytecodePosition::caller(frame);
  if (caller_frame != NULL) {
    record_scope(pc_offset, caller_frame, objects);
  }

  oop hotspot_method = BytecodePosition::method(frame);
  Method* method = getMethodFromHotSpotMethod(hotspot_method);
  jint bci = BytecodePosition::bci(frame);
  bool reexecute;
  if (bci == -1 || bci == -2){
     reexecute = false;
  } else {
    Bytecodes::Code code = Bytecodes::java_code_at(method, method->bcp_from(bci));
    reexecute = Interpreter::bytecode_should_reexecute(code);
    if (frame != NULL) {
      reexecute = (BytecodeFrame::duringCall(frame) == JNI_FALSE);
    }
  }

  if (TraceGraal >= 2) {
    tty->print_cr("Recording scope pc_offset=%d bci=%d frame=%d", pc_offset, bci, frame);
  }

  jint local_count = BytecodeFrame::numLocals(frame);
  jint expression_count = BytecodeFrame::numStack(frame);
  jint monitor_count = BytecodeFrame::numLocks(frame);
  arrayOop values = (arrayOop) BytecodeFrame::values(frame);

  assert(local_count + expression_count + monitor_count == values->length(), "unexpected values length");

  GrowableArray<ScopeValue*>* locals = new GrowableArray<ScopeValue*> ();
  GrowableArray<ScopeValue*>* expressions = new GrowableArray<ScopeValue*> ();
  GrowableArray<MonitorValue*>* monitors = new GrowableArray<MonitorValue*> ();

  if (TraceGraal >= 2) {
    tty->print_cr("Scope at bci %d with %d values", bci, values->length());
    tty->print_cr("%d locals %d expressions, %d monitors", local_count, expression_count, monitor_count);
  }

  for (jint i = 0; i < values->length(); i++) {
    ScopeValue* second = NULL;
    oop value = ((oop*) values->base(T_OBJECT))[i];

    if (i < local_count) {
      ScopeValue* first = get_hotspot_value(value, _total_frame_size, objects, second, _oop_recorder);
      if (second != NULL) {
        locals->append(second);
      }
      locals->append(first);
    } else if (i < local_count + expression_count) {
      ScopeValue* first = get_hotspot_value(value, _total_frame_size, objects, second, _oop_recorder);
      if (second != NULL) {
        expressions->append(second);
      }
      expressions->append(first);
    } else {
      monitors->append(get_monitor_value(value, _total_frame_size, objects, _oop_recorder));
    }
    if (second != NULL) {
      i++;
      assert(i < values->length(), "double-slot value not followed by Value.ILLEGAL");
      assert(((oop*) values->base(T_OBJECT))[i] == Value::ILLEGAL(), "double-slot value not followed by Value.ILLEGAL");
    }
  }

  _debug_recorder->dump_object_pool(objects);

  DebugToken* locals_token = _debug_recorder->create_scope_values(locals);
  DebugToken* expressions_token = _debug_recorder->create_scope_values(expressions);
  DebugToken* monitors_token = _debug_recorder->create_monitor_values(monitors);

  GrowableArray<DeferredWriteValue*>* deferred_writes = new GrowableArray<DeferredWriteValue*> ();
//  deferred_writes->append(new DeferredWriteValue(new LocationValue(Location::new_reg_loc(Location::lng, rax->as_VMReg())), new ConstantIntValue(0), 0, 100, new ConstantIntValue(123)));
  DebugToken* deferred_writes_token = _debug_recorder->create_deferred_writes(deferred_writes);

  bool throw_exception = BytecodeFrame::rethrowException(frame) == JNI_TRUE;

  _debug_recorder->describe_scope(pc_offset, method, NULL, bci, reexecute, throw_exception, false, false, locals_token, expressions_token, monitors_token, deferred_writes_token);
}

void CodeInstaller::site_Safepoint(CodeBuffer& buffer, jint pc_offset, oop site) {
  oop debug_info = CompilationResult_Safepoint::debugInfo(site);
  assert(debug_info != NULL, "debug info expected");

  // address instruction = _instructions->start() + pc_offset;
  // jint next_pc_offset = Assembler::locate_next_instruction(instruction) - _instructions->start();
  _debug_recorder->add_safepoint(pc_offset, -1, create_oop_map(_total_frame_size, _parameter_count, debug_info));

  oop code_pos = DebugInfo::bytecodePosition(debug_info);
  record_scope(pc_offset, code_pos, new GrowableArray<ScopeValue*>());

  _debug_recorder->end_safepoint(pc_offset);
}

void CodeInstaller::site_Call(CodeBuffer& buffer, jint pc_offset, oop site) {
  oop target = CompilationResult_Call::target(site);
  InstanceKlass* target_klass = InstanceKlass::cast(target->klass());

  oop hotspot_method = NULL; // JavaMethod
  oop global_stub = NULL;

  if (target_klass->is_subclass_of(SystemDictionary::Long_klass())) {
    global_stub = target;
  } else {
    hotspot_method = target;
  }

  oop debug_info = CompilationResult_Call::debugInfo(site);

  assert((hotspot_method ? 1 : 0) + (global_stub ? 1 : 0) == 1, "Call site needs exactly one type");

  NativeInstruction* inst = nativeInstruction_at(_instructions->start() + pc_offset);
  jint next_pc_offset = 0x0;
  bool is_call_reg = false;
  if (inst->is_call() || inst->is_jump()) {
    assert(NativeCall::instruction_size == (int)NativeJump::instruction_size, "unexpected size");
    next_pc_offset = pc_offset + NativeCall::instruction_size;
  } else if (inst->is_mov_literal64()) {
    // mov+call instruction pair
    next_pc_offset = pc_offset + NativeMovConstReg::instruction_size;
    u_char* call = (u_char*) (_instructions->start() + next_pc_offset);
    assert((call[0] == 0x40 || call[0] == 0x41) && call[1] == 0xFF, "expected call with rex/rexb prefix byte");
    next_pc_offset += 3; /* prefix byte + opcode byte + modrm byte */
  } else if (inst->is_call_reg()) {
    // the inlined vtable stub contains a "call register" instruction
    assert(hotspot_method != NULL, "only valid for virtual calls");
    is_call_reg = true;
    next_pc_offset = pc_offset + ((NativeCallReg *) inst)->next_instruction_offset();
  } else {
    tty->print_cr("at pc_offset %d", pc_offset);
    fatal("unsupported type of instruction for call site");
  }

  if (target->is_a(SystemDictionary::HotSpotInstalledCode_klass())) {
    assert(inst->is_jump(), "jump expected");

    nmethod* nm = (nmethod*) HotSpotInstalledCode::nmethod(target);
    nativeJump_at((address)inst)->set_jump_destination(nm->verified_entry_point());
    _instructions->relocate((address)inst, runtime_call_Relocation::spec(), Assembler::call32_operand);

    return;
  }

  if (debug_info != NULL) {
    oop frame = DebugInfo::bytecodePosition(debug_info);
    _debug_recorder->add_safepoint(next_pc_offset, BytecodeFrame::leafGraphId(frame), create_oop_map(_total_frame_size, _parameter_count, debug_info));
    record_scope(next_pc_offset, frame, new GrowableArray<ScopeValue*>());
  }

  if (global_stub != NULL) {
    assert(java_lang_boxing_object::is_instance(global_stub, T_LONG), "global_stub needs to be of type Long");

    if (inst->is_call()) {
      // NOTE: for call without a mov, the offset must fit a 32-bit immediate
      //       see also CompilerToVM.getMaxCallTargetOffset()
      NativeCall* call = nativeCall_at((address) (inst));
      call->set_destination(VmIds::getStub(global_stub));
      _instructions->relocate(call->instruction_address(), runtime_call_Relocation::spec(), Assembler::call32_operand);
    } else if (inst->is_mov_literal64()) {
      NativeMovConstReg* mov = nativeMovConstReg_at((address) (inst));
      mov->set_data((intptr_t) VmIds::getStub(global_stub));
      _instructions->relocate(mov->instruction_address(), runtime_call_Relocation::spec(), Assembler::imm_operand);
    } else {
      NativeJump* jump = nativeJump_at((address) (inst));
      jump->set_jump_destination(VmIds::getStub(global_stub));
      _instructions->relocate((address)inst, runtime_call_Relocation::spec(), Assembler::call32_operand);
    }
    TRACE_graal_3("relocating (stub)  at %p", inst);
  } else { // method != NULL
    assert(hotspot_method != NULL, "unexpected JavaMethod");
#ifdef ASSERT
    Method* method = NULL;
    // we need to check, this might also be an unresolved method
    if (hotspot_method->is_a(HotSpotResolvedJavaMethod::klass())) {
      method = getMethodFromHotSpotMethod(hotspot_method);
    }
#endif
    assert(debug_info != NULL, "debug info expected");

    TRACE_graal_3("method call");
    switch (_next_call_type) {
      case MARK_INLINE_INVOKEVIRTUAL: {
        break;
      }
      case MARK_INVOKEVIRTUAL:
      case MARK_INVOKEINTERFACE: {
        assert(method == NULL || !method->is_static(), "cannot call static method with invokeinterface");

        NativeCall* call = nativeCall_at(_instructions->start() + pc_offset);
        call->set_destination(SharedRuntime::get_resolve_virtual_call_stub());
        _instructions->relocate(call->instruction_address(), virtual_call_Relocation::spec(_invoke_mark_pc), Assembler::call32_operand);
        break;
      }
      case MARK_INVOKESTATIC: {
        assert(method == NULL || method->is_static(), "cannot call non-static method with invokestatic");

        NativeCall* call = nativeCall_at(_instructions->start() + pc_offset);
        call->set_destination(SharedRuntime::get_resolve_static_call_stub());
        _instructions->relocate(call->instruction_address(), relocInfo::static_call_type, Assembler::call32_operand);
        break;
      }
      case MARK_INVOKESPECIAL: {
        assert(method == NULL || !method->is_static(), "cannot call static method with invokespecial");

        NativeCall* call = nativeCall_at(_instructions->start() + pc_offset);
        call->set_destination(SharedRuntime::get_resolve_opt_virtual_call_stub());
        _instructions->relocate(call->instruction_address(), relocInfo::opt_virtual_call_type, Assembler::call32_operand);
        break;
      }
      case MARK_INVOKE_INVALID:
      default:
        fatal("invalid _next_call_type value");
        break;
    }
  }
  _next_call_type = MARK_INVOKE_INVALID;
  if (debug_info != NULL) {
    _debug_recorder->end_safepoint(next_pc_offset);
  }
}

void CodeInstaller::site_DataPatch(CodeBuffer& buffer, jint pc_offset, oop site) {
  oop constant = CompilationResult_DataPatch::constant(site);
  int alignment = CompilationResult_DataPatch::alignment(site);
  bool inlined = CompilationResult_DataPatch::inlined(site) == JNI_TRUE;
  oop kind = Constant::kind(constant);

  address instruction = _instructions->start() + pc_offset;

  char typeChar = Kind::typeChar(kind);
  switch (typeChar) {
    case 'z':
    case 'b':
    case 's':
    case 'c':
    case 'i':
      fatal("int-sized values not expected in DataPatch");
      break;
    case 'f':
    case 'j':
    case 'd': {
      record_metadata_in_constant(constant, _oop_recorder);
      if (inlined) {
        address operand = Assembler::locate_operand(instruction, Assembler::imm_operand);
        *((jlong*) operand) = Constant::primitive(constant);
      } else {
        address operand = Assembler::locate_operand(instruction, Assembler::disp32_operand);
        address next_instruction = Assembler::locate_next_instruction(instruction);
        int size = _constants->size();
        if (alignment > 0) {
          guarantee(alignment <= _constants->alignment(), "Alignment inside constants section is restricted by alignment of section begin");
          size = align_size_up(size, alignment);
        }
        // we don't care if this is a long/double/etc., the primitive field contains the right bits
        address dest = _constants->start() + size;
        _constants->set_end(dest + BytesPerLong);
        *(jlong*) dest = Constant::primitive(constant);

        long disp = dest - next_instruction;
        assert(disp == (jint) disp, "disp doesn't fit in 32 bits");
        *((jint*) operand) = (jint) disp;

        _instructions->relocate(instruction, section_word_Relocation::spec((address) dest, CodeBuffer::SECT_CONSTS), Assembler::disp32_operand);
        TRACE_graal_3("relocating (%c) at %p/%p with destination at %p (%d)", typeChar, instruction, operand, dest, size);
      }
      break;
    }
    case 'a': {
      address operand = Assembler::locate_operand(instruction, Assembler::imm_operand);
      Handle obj = Constant::object(constant);

      jobject value = JNIHandles::make_local(obj());
      *((jobject*) operand) = value;
      _instructions->relocate(instruction, oop_Relocation::spec_for_immediate(), Assembler::imm_operand);
      TRACE_graal_3("relocating (oop constant) at %p/%p", instruction, operand);
      break;
    }
    default:
      fatal(err_msg("unexpected Kind (%d) in DataPatch", typeChar));
      break;
  }
}

void CodeInstaller::site_Mark(CodeBuffer& buffer, jint pc_offset, oop site) {
  oop id_obj = CompilationResult_Mark::id(site);
  arrayOop references = (arrayOop) CompilationResult_Mark::references(site);

  if (id_obj != NULL) {
    assert(java_lang_boxing_object::is_instance(id_obj, T_INT), "Integer id expected");
    jint id = id_obj->int_field(java_lang_boxing_object::value_offset_in_bytes(T_INT));

    address instruction = _instructions->start() + pc_offset;

    switch (id) {
      case MARK_UNVERIFIED_ENTRY:
        _offsets.set_value(CodeOffsets::Entry, pc_offset);
        break;
      case MARK_VERIFIED_ENTRY:
        _offsets.set_value(CodeOffsets::Verified_Entry, pc_offset);
        break;
      case MARK_OSR_ENTRY:
        _offsets.set_value(CodeOffsets::OSR_Entry, pc_offset);
        break;
      case MARK_EXCEPTION_HANDLER_ENTRY:
        _offsets.set_value(CodeOffsets::Exceptions, pc_offset);
        break;
      case MARK_DEOPT_HANDLER_ENTRY:
        _offsets.set_value(CodeOffsets::Deopt, pc_offset);
        break;
      case MARK_STATIC_CALL_STUB: {
        _instructions->relocate(instruction, metadata_Relocation::spec_for_immediate());
        assert(references->length() == 1, "static call stub needs one reference");
        oop ref = ((oop*) references->base(T_OBJECT))[0];
        address call_pc = _instructions->start() + CompilationResult_Site::pcOffset(ref);
        _instructions->relocate(instruction, static_stub_Relocation::spec(call_pc));
        break;
      }
      case MARK_INVOKEVIRTUAL:
      case MARK_INVOKEINTERFACE: {
        // Convert the initial value of the Klass* slot in an inline cache
        // from 0L to Universe::non_oop_word().
        NativeMovConstReg* n_copy = nativeMovConstReg_at(instruction);
        assert(n_copy->data() == 0, "inline cache Klass* initial value should be 0L");
        n_copy->set_data((intptr_t)Universe::non_oop_word());
      }
      case MARK_INLINE_INVOKEVIRTUAL:
      case MARK_INVOKE_INVALID:
      case MARK_INVOKESPECIAL:
      case MARK_INVOKESTATIC:
        _next_call_type = (MarkId) id;
        _invoke_mark_pc = instruction;
        break;
      case MARK_IMPLICIT_NULL:
        _implicit_exception_table.append(pc_offset, pc_offset);
        break;
      case MARK_POLL_NEAR: {
        NativeInstruction* ni = nativeInstruction_at(instruction);
        int32_t* disp = (int32_t*) Assembler::locate_operand(instruction, Assembler::disp32_operand);
        intptr_t new_disp = (intptr_t) (os::get_polling_page() + (SafepointPollOffset % os::vm_page_size())) - (intptr_t) ni;
        *disp = (int32_t)new_disp;
      }
      case MARK_POLL_FAR:
        _instructions->relocate(instruction, relocInfo::poll_type);
        break;
      case MARK_POLL_RETURN_NEAR: {
        NativeInstruction* ni = nativeInstruction_at(instruction);
        int32_t* disp = (int32_t*) Assembler::locate_operand(instruction, Assembler::disp32_operand);
        intptr_t new_disp = (intptr_t) (os::get_polling_page() + (SafepointPollOffset % os::vm_page_size())) - (intptr_t) ni;
        *disp = (int32_t)new_disp;
      }
      case MARK_POLL_RETURN_FAR:
        _instructions->relocate(instruction, relocInfo::poll_return_type);
        break;
      case MARK_KLASS_PATCHING:
      case MARK_ACCESS_FIELD_PATCHING: {
        unsigned char* byte_count = (unsigned char*) (instruction - 1);
        unsigned char* byte_skip = (unsigned char*) (instruction - 2);
        unsigned char* being_initialized_entry_offset = (unsigned char*) (instruction - 3);

        assert(*byte_skip == 5, "unexpected byte_skip");

        assert(references->length() == 2, "MARK_KLASS_PATCHING/MARK_ACCESS_FIELD_PATCHING needs 2 references");
        oop ref1 = ((oop*) references->base(T_OBJECT))[0];
        oop ref2 = ((oop*) references->base(T_OBJECT))[1];
        int i_byte_count = CompilationResult_Site::pcOffset(ref2) - CompilationResult_Site::pcOffset(ref1);
        assert(i_byte_count == (unsigned char)i_byte_count, "invalid offset");
        *byte_count = i_byte_count;
        *being_initialized_entry_offset = *byte_count + *byte_skip;

        // we need to correct the offset of a field access - it's created with MAX_INT to ensure the correct size, and HotSpot expects 0
        if (id == MARK_ACCESS_FIELD_PATCHING) {
          NativeMovRegMem* inst = nativeMovRegMem_at(_instructions->start() + CompilationResult_Site::pcOffset(ref1));
          assert(inst->offset() == max_jint, "unexpected offset value");
          inst->set_offset(0);
        }
        break;
      }
      case MARK_DUMMY_OOP_RELOCATION: {
        _instructions->relocate(instruction, oop_Relocation::spec_for_immediate(), Assembler::imm_operand);

        RelocIterator iter(_instructions, (address) instruction, (address) (instruction + 1));
        relocInfo::change_reloc_info_for_address(&iter, (address) instruction, relocInfo::oop_type, relocInfo::none);
        break;
      }
      default:
        ShouldNotReachHere();
        break;
    }
  }
}

