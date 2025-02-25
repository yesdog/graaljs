/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.js.nodes.control;

import static com.oracle.truffle.js.runtime.builtins.JSAbstractArray.arrayGetArrayType;

import java.util.Set;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.Executed;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnknownKeyException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import com.oracle.truffle.api.object.Property;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.access.IsArrayNode;
import com.oracle.truffle.js.nodes.access.JSTargetableNode;
import com.oracle.truffle.js.nodes.array.JSArrayDeleteIndexNode;
import com.oracle.truffle.js.nodes.cast.JSToPropertyKeyNode;
import com.oracle.truffle.js.nodes.cast.ToArrayIndexNode;
import com.oracle.truffle.js.nodes.instrumentation.JSTags;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.UnaryOperationTag;
import com.oracle.truffle.js.runtime.BigInt;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSConfig;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.SafeInteger;
import com.oracle.truffle.js.runtime.Strings;
import com.oracle.truffle.js.runtime.Symbol;
import com.oracle.truffle.js.runtime.builtins.JSString;
import com.oracle.truffle.js.runtime.objects.JSDynamicObject;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.JSProperty;
import com.oracle.truffle.js.runtime.util.JSClassProfile;

/**
 * 11.4.1 The delete Operator ({@code delete object[property]}).
 */
@ImportStatic(JSConfig.class)
@NodeInfo(shortName = "delete")
public abstract class DeletePropertyNode extends JSTargetableNode {
    protected final boolean strict;
    protected final JSContext context;
    @Child @Executed protected JavaScriptNode targetNode;
    @Child @Executed protected JavaScriptNode propertyNode;

    protected DeletePropertyNode(boolean strict, JSContext context, JavaScriptNode targetNode, JavaScriptNode propertyNode) {
        this.strict = strict;
        this.context = context;
        this.targetNode = targetNode;
        this.propertyNode = propertyNode;
    }

    public static DeletePropertyNode create(boolean strict, JSContext context) {
        return create(null, null, strict, context);
    }

    public static DeletePropertyNode createNonStrict(JSContext context) {
        return create(null, null, false, context);
    }

    public static DeletePropertyNode create(JavaScriptNode object, JavaScriptNode property, boolean strict, JSContext context) {
        return DeletePropertyNodeGen.create(strict, context, object, property);
    }

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        if (tag == UnaryOperationTag.class) {
            return true;
        } else {
            return super.hasTag(tag);
        }
    }

    @Override
    public Object getNodeObject() {
        return JSTags.createNodeObjectDescriptor("operator", getClass().getAnnotation(NodeInfo.class).shortName());
    }

    @Override
    public InstrumentableNode materializeInstrumentableNodes(Set<Class<? extends Tag>> materializedTags) {
        if (materializationNeeded() && materializedTags.contains(UnaryOperationTag.class)) {
            JavaScriptNode key = cloneUninitialized(propertyNode, materializedTags);
            JavaScriptNode target = cloneUninitialized(targetNode, materializedTags);
            transferSourceSectionAddExpressionTag(this, key);
            transferSourceSectionAddExpressionTag(this, target);
            DeletePropertyNode node = DeletePropertyNode.create(target, key, strict, context);
            transferSourceSectionAndTags(this, node);
            return node;
        } else {
            return this;
        }
    }

    private boolean materializationNeeded() {
        // Both nodes must have a source section in order to be instrumentable.
        return !(propertyNode.hasSourceSection() && targetNode.hasSourceSection());
    }

    @Override
    public final JavaScriptNode getTarget() {
        return targetNode;
    }

    @Override
    public final Object execute(VirtualFrame frame) {
        return executeWithTarget(frame, evaluateTarget(frame));
    }

    @Override
    public final Object evaluateTarget(VirtualFrame frame) {
        return getTarget().execute(frame);
    }

    public abstract boolean executeEvaluated(Object objectResult, Object propertyResult);

    @Specialization(guards = {"isJSOrdinaryObject(targetObject)"})
    protected final boolean doJSOrdinaryObject(JSDynamicObject targetObject, Object key,
                    @Shared("toPropertyKey") @Cached("create()") JSToPropertyKeyNode toPropertyKeyNode,
                    @CachedLibrary(limit = "InteropLibraryLimit") DynamicObjectLibrary dynamicObjectLib) {
        Object propertyKey = toPropertyKeyNode.execute(key);

        Property foundProperty = dynamicObjectLib.getProperty(targetObject, propertyKey);
        if (foundProperty != null) {
            if (!JSProperty.isConfigurable(foundProperty)) {
                if (strict) {
                    throw Errors.createTypeErrorNotConfigurableProperty(propertyKey);
                } else {
                    return false;
                }
            }
            dynamicObjectLib.removeKey(targetObject, propertyKey);
            return true;
        } else {
            /* the prototype might have a property with that name, but we don't care */
            return true;
        }
    }

    @Specialization(guards = {"!isJSOrdinaryObject(targetObject)"})
    protected final boolean doJSObject(JSDynamicObject targetObject, Object key,
                    @Cached("createIsFastArray()") IsArrayNode isArrayNode,
                    @Cached("createBinaryProfile()") ConditionProfile arrayProfile,
                    @Cached ToArrayIndexNode toArrayIndexNode,
                    @Cached("createBinaryProfile()") ConditionProfile arrayIndexProfile,
                    @Cached("create(context, strict)") JSArrayDeleteIndexNode deleteArrayIndexNode,
                    @Cached JSClassProfile jsclassProfile,
                    @Shared("toPropertyKey") @Cached JSToPropertyKeyNode toPropertyKeyNode) {
        final Object propertyKey;
        if (arrayProfile.profile(isArrayNode.execute(targetObject))) {
            Object objIndex = toArrayIndexNode.execute(key);

            if (arrayIndexProfile.profile(objIndex instanceof Long)) {
                long longIndex = (long) objIndex;
                return deleteArrayIndexNode.execute(targetObject, arrayGetArrayType(targetObject), longIndex);
            } else {
                propertyKey = objIndex;
            }
        } else {
            propertyKey = toPropertyKeyNode.execute(key);
        }
        return JSObject.delete(targetObject, propertyKey, strict, jsclassProfile);
    }

    @SuppressWarnings("unused")
    @Specialization
    protected static boolean doSymbol(Symbol target, Object property,
                    @Shared("toPropertyKey") @Cached JSToPropertyKeyNode toPropertyKeyNode) {
        toPropertyKeyNode.execute(property);
        return true;
    }

    @SuppressWarnings("unused")
    @Specialization
    protected static boolean doSafeInteger(SafeInteger target, Object property,
                    @Shared("toPropertyKey") @Cached JSToPropertyKeyNode toPropertyKeyNode) {
        toPropertyKeyNode.execute(property);
        return true;
    }

    @SuppressWarnings("unused")
    @Specialization
    protected static boolean doBigInt(BigInt target, Object property,
                    @Shared("toPropertyKey") @Cached JSToPropertyKeyNode toPropertyKeyNode) {
        toPropertyKeyNode.execute(property);
        return true;
    }

    @Specialization
    protected boolean doString(TruffleString target, Object property,
                    @Shared("toArrayIndex") @Cached ToArrayIndexNode toArrayIndexNode,
                    @Cached TruffleString.EqualNode equalsNode) {
        Object objIndex = toArrayIndexNode.execute(property);
        boolean result;
        if (objIndex instanceof Long) {
            long index = (Long) objIndex;
            result = (index < 0) || (Strings.length(target) <= index);
        } else {
            result = !Strings.equals(equalsNode, JSString.LENGTH, (TruffleString) objIndex);
        }
        if (strict && !result) {
            throw Errors.createTypeError("cannot delete index");
        }
        return result;
    }

    @Specialization(guards = {"isForeignObject(target)", "!interop.hasArrayElements(target)"})
    protected boolean member(Object target, TruffleString name,
                    @Shared("interop") @CachedLibrary(limit = "InteropLibraryLimit") InteropLibrary interop) {
        if (context.getContextOptions().hasForeignHashProperties() && interop.hasHashEntries(target)) {
            try {
                interop.removeHashEntry(target, name);
                return true;
            } catch (UnknownKeyException e) {
                // fall through: still need to try members
            } catch (UnsupportedMessageException e) {
                if (strict) {
                    throw Errors.createTypeErrorInteropException(target, e, "delete", this);
                }
                return false;
            }
        }
        String javaName = Strings.toJavaString(name);
        if (interop.isMemberExisting(target, javaName)) {
            try {
                interop.removeMember(target, javaName);
                return true;
            } catch (UnknownIdentifierException | UnsupportedMessageException e) {
                if (strict) {
                    throw Errors.createTypeErrorCannotDeletePropertyOf(name, target);
                }
                return false;
            }
        }
        return true;
    }

    @Specialization(guards = {"isForeignObject(target)", "interop.hasArrayElements(target)"})
    protected boolean arrayElementInt(Object target, int index,
                    @Shared("interop") @CachedLibrary(limit = "InteropLibraryLimit") InteropLibrary interop) {
        return arrayElementLong(target, index, interop);
    }

    private boolean arrayElementLong(Object target, long index, InteropLibrary interop) {
        long length;
        try {
            length = interop.getArraySize(target);
        } catch (UnsupportedMessageException e) {
            return true;
        }
        // Foreign arrays cannot have holes, so we do not support deleting elements.
        // Therefore, we treat them like Typed Arrays: array elements are not configurable
        // and cannot be deleted but deleting out of bounds is always successful.
        if (index >= 0 && index < length) {
            if (strict) {
                throw Errors.createTypeErrorNotConfigurableProperty(Strings.fromLong(index));
            }
            return false;
        } else {
            return true;
        }
    }

    private boolean hashEntry(Object target, Object key, InteropLibrary interop) {
        try {
            interop.removeHashEntry(target, key);
            return true;
        } catch (UnknownKeyException e) {
            return true;
        } catch (UnsupportedMessageException e) {
            if (strict) {
                throw Errors.createTypeErrorInteropException(target, e, "delete", this);
            }
            return false;
        }
    }

    @SuppressWarnings("unused")
    @Specialization(guards = {"isForeignObject(target)"}, replaces = {"member", "arrayElementInt"})
    protected boolean foreignObject(Object target, Object key,
                    @Shared("interop") @CachedLibrary(limit = "InteropLibraryLimit") InteropLibrary interop,
                    @Shared("toArrayIndex") @Cached("create()") ToArrayIndexNode toArrayIndexNode,
                    @Shared("toPropertyKey") @Cached("create()") JSToPropertyKeyNode toPropertyKeyNode) {
        Object propertyKey;
        if (interop.hasArrayElements(target)) {
            Object indexOrPropertyKey = toArrayIndexNode.execute(key);
            if (indexOrPropertyKey instanceof Long) {
                return arrayElementLong(target, (long) indexOrPropertyKey, interop);
            } else {
                propertyKey = indexOrPropertyKey;
                assert JSRuntime.isPropertyKey(propertyKey);
            }
        } else {
            propertyKey = toPropertyKeyNode.execute(key);
        }
        if (context.getContextOptions().hasForeignHashProperties() && interop.hasHashEntries(target)) {
            return hashEntry(target, propertyKey, interop);
        }
        if (interop.hasMembers(target)) {
            if (Strings.isTString(propertyKey)) {
                return member(target, (TruffleString) propertyKey, interop);
            } else {
                assert propertyKey instanceof Symbol;
                return true;
            }
        } else {
            return true;
        }
    }

    @SuppressWarnings("unused")
    @Specialization(guards = {"!isTruffleObject(target)", "!isString(target)"})
    public boolean doOther(Object target, Object property,
                    @Shared("toPropertyKey") @Cached JSToPropertyKeyNode toPropertyKeyNode) {
        toPropertyKeyNode.execute(property);
        return true;
    }

    @Override
    protected JavaScriptNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
        return create(cloneUninitialized(getTarget(), materializedTags), cloneUninitialized(propertyNode, materializedTags), strict, context);
    }

    @Override
    public boolean isResultAlwaysOfType(Class<?> clazz) {
        return clazz == boolean.class;
    }
}
