/*
 * Copyright (c) 2010, 2022, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.js.parser.ir;

import com.oracle.js.parser.ir.visitor.NodeVisitor;
import com.oracle.js.parser.ir.visitor.TranslatorNodeVisitor;
import com.oracle.truffle.api.strings.TruffleString;

/**
 * IR representation for an identifier.
 */
public final class IdentNode extends Expression implements PropertyKey, FunctionCall {
    //@formatter:off
    private static final int PROPERTY_NAME     = 1 << 0;
    private static final int INITIALIZED_HERE  = 1 << 1;
    private static final int FUNCTION          = 1 << 2;
    private static final int NEW_TARGET        = 1 << 3;
    private static final int IS_DECLARED_HERE  = 1 << 4;
    private static final int THIS              = 1 << 5;
    private static final int SUPER             = 1 << 6;
    private static final int DIRECT_SUPER      = 1 << 7;
    private static final int REST_PARAMETER    = 1 << 8;
    private static final int CATCH_PARAMETER   = 1 << 9;
    private static final int IMPORT_META       = 1 << 10;
    private static final int ARGUMENTS         = 1 << 11;
    private static final int APPLY_ARGUMENTS   = 1 << 12;
    private static final int PRIVATE_IDENT     = 1 << 13;
    private static final int PRIVATE_IN_CHECK  = 1 << 14;
    //@formatter:on

    /** Identifier. */
    private final String name;
    private final TruffleString nameTS;

    private final int flags;

    private Symbol symbol;

    /**
     * Constructor
     *
     * @param token token
     * @param finish finish position
     * @param name name of identifier
     */
    public IdentNode(final long token, final int finish, final TruffleString name) {
        super(token, finish);
        this.name = name.toJavaStringUncached();
        this.nameTS = name;
        this.flags = 0;
    }

    private IdentNode(final IdentNode identNode, final String name, final TruffleString nameTS, final int flags) {
        super(identNode);
        this.name = name;
        this.nameTS = nameTS;
        this.flags = flags;
        this.symbol = identNode.symbol;
    }

    /**
     * Assist in IR navigation.
     *
     * @param visitor IR navigating visitor.
     */
    @Override
    public Node accept(final NodeVisitor<? extends LexicalContext> visitor) {
        if (visitor.enterIdentNode(this)) {
            return visitor.leaveIdentNode(this);
        }

        return this;
    }

    @Override
    public <R> R accept(TranslatorNodeVisitor<? extends LexicalContext, R> visitor) {
        return visitor.enterIdentNode(this);
    }

    @Override
    public void toString(final StringBuilder sb, final boolean printType) {
        sb.append(name);
    }

    /**
     * Get the name of the identifier
     *
     * @return IdentNode name
     */
    public String getName() {
        return name;
    }

    public TruffleString getNameTS() {
        return nameTS;
    }

    @Override
    public String getPropertyName() {
        return getName();
    }

    @Override
    public TruffleString getPropertyNameTS() {
        return nameTS;
    }

    /**
     * Return the Symbol the compiler has assigned to this identifier. The symbol is a description
     * of the storage location for the identifier.
     *
     * @return the symbol
     */
    public Symbol getSymbol() {
        return symbol;
    }

    /**
     * Check if this IdentNode is a property name
     *
     * @return true if this is a property name
     */
    public boolean isPropertyName() {
        return (flags & PROPERTY_NAME) == PROPERTY_NAME;
    }

    /**
     * Flag this IdentNode as a property name
     *
     * @return a node equivalent to this one except for the requested change.
     */
    public IdentNode setIsPropertyName() {
        if (isPropertyName()) {
            return this;
        }
        return new IdentNode(this, name, nameTS, flags | PROPERTY_NAME);
    }

    /**
     * Helper function for local def analysis.
     *
     * @return true if IdentNode is initialized on creation
     */
    public boolean isInitializedHere() {
        return (flags & INITIALIZED_HERE) == INITIALIZED_HERE;
    }

    /**
     * Flag IdentNode to be initialized on creation
     *
     * @return a node equivalent to this one except for the requested change.
     */
    public IdentNode setIsInitializedHere() {
        if (isInitializedHere()) {
            return this;
        }
        return new IdentNode(this, name, nameTS, flags | INITIALIZED_HERE);
    }

    /**
     * Is this IdentNode declared here?
     *
     * @return true if identifier is declared here
     */
    public boolean isDeclaredHere() {
        return (flags & IS_DECLARED_HERE) != 0;
    }

    /**
     * Flag this IdentNode as being declared here.
     *
     * @return a new IdentNode equivalent to this but marked as declared here.
     */
    public IdentNode setIsDeclaredHere() {
        if (isDeclaredHere()) {
            return this;
        }
        return new IdentNode(this, name, nameTS, flags | IS_DECLARED_HERE);
    }

    @Override
    public boolean isFunction() {
        return (flags & FUNCTION) == FUNCTION;
    }

    /**
     * Is this an internal symbol, i.e. one that starts with ':'. Those can never be optimistic.
     *
     * @return true if internal symbol
     */
    public boolean isInternal() {
        assert name != null;
        return getName().charAt(0) == ':';
    }

    public boolean isThis() {
        return (flags & THIS) != 0;
    }

    public IdentNode setIsThis() {
        return new IdentNode(this, name, nameTS, flags | THIS);
    }

    public boolean isSuper() {
        return (flags & SUPER) != 0;
    }

    public IdentNode setIsSuper() {
        return new IdentNode(this, name, nameTS, flags | SUPER);
    }

    public boolean isDirectSuper() {
        return (flags & DIRECT_SUPER) != 0;
    }

    public IdentNode setIsDirectSuper() {
        return new IdentNode(this, name, nameTS, flags | SUPER | DIRECT_SUPER);
    }

    public boolean isRestParameter() {
        return (flags & REST_PARAMETER) != 0;
    }

    public IdentNode setIsRestParameter() {
        return new IdentNode(this, name, nameTS, flags | REST_PARAMETER);
    }

    public boolean isCatchParameter() {
        return (flags & CATCH_PARAMETER) != 0;
    }

    public IdentNode setIsCatchParameter() {
        return new IdentNode(this, name, nameTS, flags | CATCH_PARAMETER);
    }

    public boolean isNewTarget() {
        return (flags & NEW_TARGET) != 0;
    }

    public IdentNode setIsNewTarget() {
        return new IdentNode(this, name, nameTS, flags | NEW_TARGET);
    }

    public boolean isImportMeta() {
        return (flags & IMPORT_META) != 0;
    }

    public IdentNode setIsImportMeta() {
        return new IdentNode(this, name, nameTS, flags | IMPORT_META);
    }

    public boolean isMetaProperty() {
        return isNewTarget() || isImportMeta();
    }

    public IdentNode setIsArguments() {
        return new IdentNode(this, name, nameTS, flags | ARGUMENTS);
    }

    public boolean isArguments() {
        return (flags & ARGUMENTS) != 0;
    }

    public IdentNode setIsApplyArguments() {
        return new IdentNode(this, name, nameTS, flags | APPLY_ARGUMENTS);
    }

    public boolean isApplyArguments() {
        return (flags & APPLY_ARGUMENTS) != 0;
    }

    public IdentNode setIsPrivate() {
        return new IdentNode(this, name, nameTS, flags | PRIVATE_IDENT);
    }

    public boolean isPrivate() {
        return (flags & PRIVATE_IDENT) != 0;
    }

    public IdentNode setIsPrivateInCheck() {
        return new IdentNode(this, name, nameTS, flags | PRIVATE_IN_CHECK);
    }

    public boolean isPrivateInCheck() {
        return (flags & PRIVATE_IN_CHECK) != 0;
    }
}
