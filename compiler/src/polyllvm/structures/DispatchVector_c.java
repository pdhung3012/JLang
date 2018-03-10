package polyllvm.structures;

import org.bytedeco.javacpp.LLVM;
import org.bytedeco.javacpp.LLVM.*;
import polyglot.types.ClassType;
import polyglot.types.FieldInstance;
import polyglot.types.MethodInstance;
import polyglot.types.ReferenceType;
import polyllvm.util.Constants;
import polyllvm.visit.LLVMTranslator;

import java.lang.Override;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.bytedeco.javacpp.LLVM.*;

// TODO
// There's still some cleanup to do here, namely, moving all dispatch vector code
// from LLVMUtils and LLVMTranslator into this class. May also want to create new classes
// to specifically manage the methods array and interface method hash table.

public class DispatchVector_c implements DispatchVector {
    protected final LLVMTranslator v;
    protected final Map<ClassType, LLVMTypeRef> typeCache = new HashMap<>();
    protected final Map<ClassType, List<MethodInstance>> methodCached = new HashMap<>();

    public DispatchVector_c(LLVMTranslator v) {
        this.v = v;
    }

    /** The high-level layout of a dispatch vector. */
    private enum Layout {

        CLASS_OBJECT {
            // A pointer to the java.lang.Class representing this class type.
            @Override
            LLVMValueRef buildValueRef(DispatchVector_c o, ClassType erased) {
                FieldInstance classObj = erased.fieldNamed(Constants.CLASS_OBJECT);
                String mangledName = o.v.mangler.mangleStaticFieldName(classObj);
                LLVMTypeRef elemType = o.v.utils.toLL(classObj.type());
                return o.v.utils.getGlobal(mangledName, elemType);
            }
        },

        INTERFACE_METHOD_HASH_TABLE {
            // A hash table for interface method dispatch.
            @Override
            LLVMValueRef buildValueRef(DispatchVector_c o, ClassType erased) {
                // The pointer will be initialized at runtime.
                return LLVMConstNull(o.v.utils.llvmBytePtr());
            }
        },

        SUPER_TYPES {
            // A contiguous array of all super types for cache-efficient instanceof checks.
            @Override
            LLVMValueRef buildValueRef(DispatchVector_c o, ClassType erased) {
                return o.v.classObjs.classObjRef(erased);
            }
        },

        METHODS {
            // Method pointers for instance method dispatch.
            @Override
            LLVMValueRef buildValueRef(DispatchVector_c o, ClassType erased) {

                // Convert a method instance into a function pointer.
                Function<MethodInstance, LLVMValueRef> getFuncPtr = mi -> {
                    String name = o.v.mangler.mangleProcName(mi);
                    LLVMTypeRef type = o.v.utils.toLLFuncTy(
                            mi.container(), mi.returnType(), mi.formalTypes());
                    return o.v.utils.getFunction(name, type);
                };

                // Collect all function pointers, casted to byte pointers.
                LLVMTypeRef funcPtrTy = o.v.utils.llvmBytePtr();
                List<MethodInstance> methods = o.v.cdvMethods(o.v.utils.erasureLL(erased));
                LLVMValueRef[] funcPtrs = methods.stream()
                        .map(getFuncPtr)
                        .map(func -> LLVMConstBitCast(func, funcPtrTy))
                        .toArray(LLVMValueRef[]::new);

                return o.v.utils.buildConstArray(funcPtrTy, funcPtrs);
            }
        };

        abstract LLVMValueRef buildValueRef(DispatchVector_c o, ClassType erased);

        static LLVMValueRef[] buildComponentValueRefs(DispatchVector_c o, ClassType erased) {
            return Stream.of(Layout.values())
                    .map((c) -> c.buildValueRef(o, erased))
                    .toArray(LLVMValueRef[]::new);
        }
    }

    @Override
    public LLVMTypeRef structTypeRef(ReferenceType rt) {
        ClassType erased = v.utils.erasureLL(rt); // Erase generic types!
        return typeCache.computeIfAbsent(erased, (key) -> {
            String mangledName = v.mangler.cdvTyName(erased);
            return v.utils.getOrCreateNamedOpaqueStruct(mangledName);
        });
    }

    /**
     * Same as {@link DispatchVector#structTypeRef(ReferenceType)}, but ensures that
     * the struct is non-opaque.
     */
    protected LLVMTypeRef structTypeRefNonOpaque(ReferenceType rt) {
        ClassType erased = v.utils.erasureLL(rt); // Erase generic types!
        LLVMTypeRef res = structTypeRef(erased);
        v.utils.fillStructIfNeeded(res, () ->
                Stream.of(Layout.buildComponentValueRefs(this, erased))
                        .map(LLVM::LLVMTypeOf)
                        .toArray(LLVMTypeRef[]::new));
        return res;
    }

    @Override
    public void initializeDispatchVectorFor(ReferenceType rt) {
        ClassType erased = v.utils.erasureLL(rt); // Erase generic types!
        LLVMTypeRef typeRef = structTypeRefNonOpaque(erased); // Ensure non-opaque type.
        LLVMValueRef global = getDispatchVectorFor(erased);
        LLVMValueRef[] body = Layout.buildComponentValueRefs(this, erased);
        LLVMValueRef init = v.utils.buildNamedConstStruct(typeRef, body);
        LLVMSetInitializer(global, init);
    }

    @Override
    public LLVMValueRef getDispatchVectorFor(ReferenceType rt) {
        return v.utils.getGlobal(v.mangler.cdvGlobalId(rt), structTypeRef(rt));
    }

    @Override
    public LLVMValueRef buildFuncElementPtr(
            LLVMValueRef dvPtr, ReferenceType recvTy, MethodInstance pi) {
        structTypeRefNonOpaque(recvTy); // Erase non-opaque type.
        int idx = v.dispatchInfo(recvTy, pi).methodIndex();
        return v.utils.buildStructGEP(dvPtr, 0, Layout.METHODS.ordinal(), idx);
    }
}
