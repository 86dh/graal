/*
 * Copyright (c) 2022, 2024, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.regex.tregex.parser;

import java.util.ArrayList;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.regex.RegexFlags;
import com.oracle.truffle.regex.charset.CodePointSet;
import com.oracle.truffle.regex.charset.Constants;
import com.oracle.truffle.regex.tregex.TRegexOptions;
import com.oracle.truffle.regex.tregex.buffer.CompilationBuffer;
import com.oracle.truffle.regex.tregex.parser.ast.BackReference;
import com.oracle.truffle.regex.tregex.parser.ast.CalcASTFlagsVisitor;
import com.oracle.truffle.regex.tregex.parser.ast.CalcASTPropsVisitor;
import com.oracle.truffle.regex.tregex.parser.ast.CharacterClass;
import com.oracle.truffle.regex.tregex.parser.ast.Group;
import com.oracle.truffle.regex.tregex.parser.ast.LookAroundAssertion;
import com.oracle.truffle.regex.tregex.parser.ast.PositionAssertion;
import com.oracle.truffle.regex.tregex.parser.ast.QuantifiableTerm;
import com.oracle.truffle.regex.tregex.parser.ast.RegexAST;
import com.oracle.truffle.regex.tregex.parser.ast.RegexASTNode;
import com.oracle.truffle.regex.tregex.parser.ast.Sequence;
import com.oracle.truffle.regex.tregex.parser.ast.SubexpressionCall;
import com.oracle.truffle.regex.tregex.parser.ast.Term;
import com.oracle.truffle.regex.tregex.parser.ast.visitors.CopyVisitor;
import com.oracle.truffle.regex.tregex.parser.ast.visitors.DepthFirstTraversalRegexASTVisitor;
import com.oracle.truffle.regex.tregex.parser.ast.visitors.InitIDVisitor;
import com.oracle.truffle.regex.tregex.parser.ast.visitors.MarkLookBehindEntriesVisitor;
import com.oracle.truffle.regex.tregex.parser.ast.visitors.NodeCountVisitor;
import com.oracle.truffle.regex.tregex.parser.ast.visitors.PropagateDeadFlagVisitor;
import com.oracle.truffle.regex.tregex.string.Encodings;

public class RegexASTPostProcessor {

    private final RegexAST ast;
    private final RegexProperties properties;
    private final RegexFlags flags;
    private final CompilationBuffer compilationBuffer;

    public RegexASTPostProcessor(RegexAST ast, CompilationBuffer compilationBuffer) {
        this.ast = ast;
        this.properties = ast.getProperties();
        this.flags = ast.getFlags();
        this.compilationBuffer = compilationBuffer;
    }

    public void prepareForDFA() {
        CalcASTFlagsVisitor.run(ast);
        if (ast.getOptions().isBooleanMatch()) {
            DisableCaptureGroupsVisitor.disableCaptureGroups(ast);
        }
        OptimizeLookAroundsVisitor.optimizeLookArounds(ast, compilationBuffer);
        if (properties.hasQuantifiers()) {
            UnrollQuantifiersVisitor.unrollQuantifiers(ast);
        }
        CalcASTPropsVisitor.run(ast, compilationBuffer);
        PropagateDeadFlagVisitor.propagateDeadFlag(ast.getRoot());
        ast.createPrefix();
        InitIDVisitor.init(ast);
        if (ast.canTransformToDFA()) {
            new MarkLookBehindEntriesVisitor(ast).run();
        }
        checkInnerLiteral();
    }

    private void checkInnerLiteral() {
        if (ast.isLiteralString() || ast.getRoot().startsWithCaret() || ast.getRoot().endsWithDollar() || ast.getRoot().size() != 1 || flags.isSticky()) {
            return;
        }
        ArrayList<Term> terms = ast.getRoot().getFirstAlternative().getTerms();
        int literalStart = -1;
        int literalEnd = -1;
        for (int i = 0; i < terms.size(); i++) {
            Term t = terms.get(i);
            if (isLiteralChar(t)) {
                assert !t.hasLoops();
                if (literalStart < 0) {
                    literalStart = i;
                }
                literalEnd = i + 1;
            } else if (literalStart >= 0 || t.hasBackReferences()) {
                break;
            }
        }
        if (literalStart >= 0 && (literalStart > 0 || literalEnd - literalStart > 0)) {
            properties.setInnerLiteral(literalStart, literalEnd);
        }
    }

    private boolean isLiteralChar(Term t) {
        return t.isCharacterClass() &&
                        (t.asCharacterClass().getCharSet().matchesSingleChar() || t.asCharacterClass().getCharSet().matches2CharsWith1BitDifference()) &&
                        ast.getEncoding().isFixedCodePointWidth(t.asCharacterClass().getCharSet()) &&
                        !(ast.getEncoding() == Encodings.UTF_16 && t.asCharacterClass().getCharSet().intersects(Constants.SURROGATES));
    }

    private static final class UnrollQuantifiersVisitor extends DepthFirstTraversalRegexASTVisitor {

        private final RegexAST ast;
        private final ShouldUnrollQuantifierVisitor shouldUnrollVisitor = new ShouldUnrollQuantifierVisitor();
        private final QuantifierExpander quantifierExpander;

        private UnrollQuantifiersVisitor(RegexAST ast) {
            this.ast = ast;
            this.quantifierExpander = new QuantifierExpander(ast);
        }

        public static void unrollQuantifiers(RegexAST ast) {
            new UnrollQuantifiersVisitor(ast).run(ast.getRoot());
        }

        @Override
        protected void visit(BackReference backReference) {
            if (backReference.hasQuantifier()) {
                quantifierExpander.expandQuantifier(backReference, shouldUnroll(backReference));
            }
        }

        @Override
        protected void visit(CharacterClass characterClass) {
            if (characterClass.hasQuantifier()) {
                quantifierExpander.expandQuantifier(characterClass, shouldUnroll(characterClass));
            }
        }

        @Override
        protected void leave(Group group) {
            if (group.hasQuantifier()) {
                quantifierExpander.expandQuantifier(group, (shouldUnroll(group) && shouldUnrollVisitor.shouldUnroll(group)));
            }
        }

        @Override
        protected void visit(SubexpressionCall subexpressionCall) {
            throw CompilerDirectives.shouldNotReachHere("subexpression calls should be expanded by the parser");
        }

        private boolean shouldUnroll(QuantifiableTerm term) {
            return ast.getNumberOfNodes() <= TRegexOptions.TRegexMaxParseTreeSizeForDFA && (term.getQuantifier().isUnrollTrivial() || term.isUnrollingCandidate(ast.getOptions()));
        }

        private static final class ShouldUnrollQuantifierVisitor extends NodeCountVisitor {

            private boolean containsBackReference;

            boolean shouldUnroll(Group group) {
                assert group.hasQuantifier();
                if (group.getQuantifier().isUnrollTrivial()) {
                    return true;
                }
                count = 0;
                containsBackReference = false;
                run(group);
                return count <= TRegexOptions.TRegexQuantifierUnrollLimitGroupNodeCount && !containsBackReference;
            }

            @Override
            protected void visit(BackReference backReference) {
                super.visit(backReference);
                containsBackReference = true;
            }
        }

        private static final class QuantifierExpander {

            private final RegexAST ast;

            private final CopyVisitor copyVisitor;
            private final ClearRegisteredCaptureGroupsVisitor clearRegisteredCaptureGroupsVisitor;
            private Group curGroup;
            private Sequence curSequence;
            private Term curTerm;

            QuantifierExpander(RegexAST ast) {
                this.ast = ast;
                this.copyVisitor = new CopyVisitor(ast);
                this.clearRegisteredCaptureGroupsVisitor = new ClearRegisteredCaptureGroupsVisitor(ast);
            }

            private void pushGroup() {
                curGroup = ast.createGroup();
                curSequence.add(curGroup);
                nextSequence();
            }

            private void replaceCurTermWithNewGroup() {
                curGroup = ast.createGroup();
                curSequence.replace(curTerm.getSeqIndex(), curGroup);
                nextSequence();
            }

            private void popGroup() {
                curTerm = curGroup;
                curSequence = curGroup.getParent().asSequence();
                curGroup = curSequence.getParent();
            }

            private void nextSequence() {
                curSequence = curGroup.addSequence(ast);
                curTerm = null;
            }

            private void addTerm(Term term) {
                curSequence.add(term);
                curTerm = term;
            }

            private void addTermCopyAsGroup(Term term) {
                if (term.isGroup()) {
                    addTerm(copyVisitor.copy(term));
                } else {
                    pushGroup();
                    addTerm(copyVisitor.copy(term));
                    popGroup();
                    if (term.isGroup()) {
                        curTerm.asGroup().setEnclosedCaptureGroupsLo(term.asGroup().getCaptureGroupsLo());
                        curTerm.asGroup().setEnclosedCaptureGroupsHi(term.asGroup().getCaptureGroupsHi());
                    }
                }
            }

            private void createOptionalBranch(QuantifiableTerm term, Token.Quantifier quantifier, boolean unroll, boolean mandatory, boolean optional, int recurse) {
                // We wrap the quantified term in a group, as NFATraversalRegexASTVisitor is set up
                // to expect quantifier guards only on group boundaries.
                if (term.isInLookBehindAssertion()) {
                    createOptional(term, quantifier, unroll, mandatory, optional, recurse - 1);
                }
                addTermCopyAsGroup(term);
                curTerm.asGroup().setQuantifier(quantifier);
                curTerm.setExpandedQuantifier(unroll);
                curTerm.setMandatoryQuantifier(mandatory);
                curTerm.setOptionalQuantifier(optional);
                curTerm.setEmptyGuard(true);
                if (!term.isInLookBehindAssertion()) {
                    createOptional(term, quantifier, unroll, mandatory, optional, recurse - 1);
                }
            }

            private void createOptional(QuantifiableTerm term, Token.Quantifier quantifier, boolean unroll, boolean mandatory, boolean optional, int recurse) {
                if (recurse < 0) {
                    return;
                }
                pushGroup();
                if (term.isGroup()) {
                    curGroup.setEnclosedCaptureGroupsLo(term.asGroup().getCaptureGroupsLo());
                    curGroup.setEnclosedCaptureGroupsHi(term.asGroup().getCaptureGroupsHi());
                }
                if (quantifier.isGreedy() || mandatory) {
                    createOptionalBranch(term, quantifier, unroll, mandatory, optional, recurse);
                    nextSequence();
                    curSequence.setQuantifierPassThroughSequence(true);
                } else {
                    curSequence.setQuantifierPassThroughSequence(true);
                    nextSequence();
                    createOptionalBranch(term, quantifier, unroll, false, optional, recurse);
                }
                if (!unroll && !mandatory && recurse == 0) {
                    curGroup.setLoop(true);
                }
                popGroup();
            }

            private void expandQuantifier(QuantifiableTerm toExpand, boolean unroll) {
                assert toExpand.hasQuantifier();
                assert !(unroll && !toExpand.isUnrollingCandidate(ast.getOptions()));
                clearRegisteredCaptureGroupsVisitor.clear(toExpand);
                Token.Quantifier quantifier = toExpand.getQuantifier();
                toExpand.setQuantifier(null);

                curTerm = toExpand;
                curSequence = (Sequence) curTerm.getParent();
                curGroup = curSequence.getParent();

                // replace the term to expand with a new wrapper group
                replaceCurTermWithNewGroup();

                boolean mandatoryOptionalSplit = !unroll && !ast.getFlavor().emptyChecksOnMandatoryLoopIterations() && quantifier.getMin() > 0 && toExpand.mayMatchEmptyString();

                if (toExpand.isInLookBehindAssertion()) {
                    unrollOptional(toExpand, quantifier, unroll, mandatoryOptionalSplit);
                    unrollMandatory(toExpand, quantifier, unroll, mandatoryOptionalSplit);
                } else {
                    unrollMandatory(toExpand, quantifier, unroll, mandatoryOptionalSplit);
                    unrollOptional(toExpand, quantifier, unroll, mandatoryOptionalSplit);
                }
            }

            private void unrollMandatory(QuantifiableTerm toExpand, Token.Quantifier quantifier, boolean unroll, boolean mandatoryOptionalSplit) {
                // unroll mandatory part ( x{3} -> xxx )
                if (unroll) {
                    for (int i = 0; i < quantifier.getMin(); i++) {
                        addTermCopyAsGroup(toExpand);
                        curTerm.asGroup().setQuantifier(quantifier);
                        curTerm.setExpandedQuantifier(true);
                        curTerm.setMandatoryQuantifier(true);
                    }
                } else if (mandatoryOptionalSplit) {
                    createOptional(toExpand, quantifier, false, true, false, 0);
                    ((Group) curTerm).setLoop(true);
                }
            }

            private void unrollOptional(QuantifiableTerm toExpand, Token.Quantifier quantifier, boolean unroll, boolean mandatoryOptionalSplit) {
                // unroll optional part ( x{0,3} -> (x(x(x|)|)|) )
                // In flavors like Python or Ruby, loops can be repeated past the point where the
                // position in the string keeps advancing (i.e. we are matching at least one
                // character per iteration). In Ruby, this can happen for as long as the state of
                // capture groups is being changed by each iteration. In Python, an extra empty
                // iteration is run because there is no backtracking after failing the empty check.
                // We can emulate this behavior by dropping empty guards in small bounded loops,
                // such as is the case for unrolled loops.
                if (unroll) {
                    createOptional(toExpand, quantifier, true, false, false, quantifier.isInfiniteLoop() ? 0 : quantifier.getMax() - quantifier.getMin() - 1);
                    if (quantifier.isInfiniteLoop()) {
                        ((Group) curTerm).setLoop(true);
                    }
                } else if (quantifier.isInfiniteLoop() || quantifier.getMax() > quantifier.getMin() || !mandatoryOptionalSplit) {
                    createOptional(toExpand, quantifier, false, false, mandatoryOptionalSplit, 0);
                }
            }
        }
    }

    private static final class OptimizeLookAroundsVisitor extends DepthFirstTraversalRegexASTVisitor {

        private final RegexAST ast;
        private final CompilationBuffer compilationBuffer;
        private final NodeCountVisitor countVisitor = new NodeCountVisitor();

        private OptimizeLookAroundsVisitor(RegexAST ast, CompilationBuffer compilationBuffer) {
            this.ast = ast;
            this.compilationBuffer = compilationBuffer;
        }

        public static void optimizeLookArounds(RegexAST ast, CompilationBuffer compilationBuffer) {
            new OptimizeLookAroundsVisitor(ast, compilationBuffer).run(ast.getRoot());
        }

        @Override
        protected void leave(Sequence sequence) {
            int i = 0;
            while (i < sequence.size()) {
                Term term = sequence.get(i);
                if (term.isLookAroundAssertion()) {
                    LookAroundOptimization opt = optimizeLookAround((LookAroundAssertion) term);
                    switch (opt.action) {
                        case NONE:
                            break;
                        case NO_OP:
                            sequence.removeTerm(i, compilationBuffer);
                            i--;
                            break;
                        case REPLACE:
                            sequence.replace(i, opt.replacement);
                            break;
                    }
                }
                i++;
            }
        }

        private static final class LookAroundOptimization {

            private enum Action {
                NONE,
                NO_OP,
                REPLACE;
            }

            private static final LookAroundOptimization NONE = new LookAroundOptimization(Action.NONE, null);
            private static final LookAroundOptimization NO_OP = new LookAroundOptimization(Action.NO_OP, null);

            private final Action action;
            private final Term replacement;

            private LookAroundOptimization(Action action, Term replacement) {
                this.action = action;
                this.replacement = replacement;
            }

            private static LookAroundOptimization replace(Term replacement) {
                return new LookAroundOptimization(Action.REPLACE, replacement);
            }
        }

        /**
         * Tries to find an optimized representation of a look-around assertion.
         */
        private LookAroundOptimization optimizeLookAround(LookAroundAssertion lookaround) {
            Group group = lookaround.getGroup();

            // Simplify lookarounds with empty branches:
            boolean hasCaptureGroups = false;
            for (int i = 0; i < group.size(); i++) {
                Sequence s = group.getAlternatives().get(i);
                // we also check for s.isEmpty here, because a previous lookaround optimization may
                // already have removed the capture groups, and we don't re-run CalcAstPropsVisitor
                // between these optimizations.
                // Example: in /(?<=(?=|()))/, we first remove the inner lookahead, so the outer
                // lookbehind is empty but still has the hasGroups flag set.
                hasCaptureGroups |= s.hasCaptureGroups() && !s.isEmpty();
                if (s.isEmpty()) {
                    if (lookaround.isNegated()) {
                        // negative lookarounds with empty branches never match
                        ast.getNodeCount().dec(countVisitor.count(lookaround));
                        return LookAroundOptimization.replace(ast.createCharacterClass(CodePointSet.getEmpty()));
                    } else {
                        // positive lookarounds with empty branches are no-ops, but we still have to
                        // keep higher priority branches if they have capture groups
                        if (hasCaptureGroups) {
                            if (group.size() > i + 1) {
                                group.getAlternatives().subList(i + 1, group.size()).clear();
                            }
                            break;
                        } else {
                            ast.getNodeCount().dec(countVisitor.count(lookaround));
                            return LookAroundOptimization.NO_OP;
                        }
                    }
                }
            }

            // Extract position assertions from positive lookarounds
            if (!lookaround.isNegated() && !lookaround.hasCaptureGroups()) {
                if (group.size() == 1 && group.getFirstAlternative().size() == 1 && group.getFirstAlternative().getFirstTerm().isPositionAssertion()) {
                    // unwrap positive lookarounds containing only a position assertion
                    // * (?=$) -> $
                    ast.getNodeCount().dec(countVisitor.count(lookaround));
                    PositionAssertion positionAssertion = (PositionAssertion) group.getFirstAlternative().getFirstTerm();
                    ast.register(positionAssertion);
                    return LookAroundOptimization.replace(positionAssertion);
                } else {
                    int innerPositionAssertion = -1;
                    for (int i = 0; i < group.size(); i++) {
                        Sequence s = group.getAlternatives().get(i);
                        if (s.size() == 1 && s.getFirstTerm().isPositionAssertion()) {
                            innerPositionAssertion = i;
                            break;
                        }
                    }
                    // extract alternatives consisting of a single position assertion
                    // * (?=...|$) -> (?:$|(?=...))
                    // * (?=...|$|...) -> (?:$|(?=...|...))
                    if (innerPositionAssertion >= 0) {
                        Sequence removed = group.getAlternatives().remove(innerPositionAssertion);
                        Group wrapGroup = ast.createGroup();
                        wrapGroup.setEnclosedCaptureGroupsLo(group.getCaptureGroupsLo());
                        wrapGroup.setEnclosedCaptureGroupsHi(group.getCaptureGroupsHi());
                        wrapGroup.add(removed);
                        Sequence wrapSeq = wrapGroup.addSequence(ast);
                        assert !group.isEmpty();
                        wrapSeq.add(lookaround);
                        return LookAroundOptimization.replace(wrapGroup);
                    }
                }
            }

            // Convert single-character-class negative lookarounds to positive ones
            // * (?!x) -> (?:$|(?=[^x]))
            // This simplifies things for the DFA generator.
            if (lookaround.isNegated() && group.size() == 1 && group.getFirstAlternative().isSingleCharClass()) {
                // we don't have to expand the inverse in unicode explode mode here, because the
                // character set is guaranteed to be in BMP range, and its inverse will match all
                // surrogates
                CharacterClass cc = group.getFirstAlternative().getFirstTerm().asCharacterClass();
                assert !ast.getFlags().isEitherUnicode() || !ast.getOptions().isUTF16ExplodeAstralSymbols() || cc.getCharSet().matchesNothing() || cc.getCharSet().getMax() <= 0xffff;
                if (cc.getCharSet().isEmpty()) {
                    // negative lookaround on a character class that can never match -> no-op
                    return LookAroundOptimization.NO_OP;
                }
                Group wrapGroup = ast.createGroup();
                Sequence positionAssertionSeq = wrapGroup.addSequence(ast);
                positionAssertionSeq.add(ast.createPositionAssertion(lookaround.isLookAheadAssertion() ? PositionAssertion.Type.DOLLAR : PositionAssertion.Type.CARET));
                Sequence wrapSeq = wrapGroup.addSequence(ast);
                wrapSeq.add(lookaround);
                lookaround.setNegated(false);
                cc.setCharSet(cc.getCharSet().createInverse(ast.getEncoding()));
                return LookAroundOptimization.replace(wrapGroup);
            }

            // No optimization found.
            return LookAroundOptimization.NONE;
        }
    }

    private static final class DisableCaptureGroupsVisitor extends DepthFirstTraversalRegexASTVisitor {

        private final RegexAST ast;

        private DisableCaptureGroupsVisitor(RegexAST ast) {
            this.ast = ast;
        }

        public static void disableCaptureGroups(RegexAST ast) {
            new DisableCaptureGroupsVisitor(ast).run(ast.getRoot());
        }

        @Override
        protected void visit(Group group) {
            if (group.isCapturing() && !ast.isGroupReferenced(group.getGroupNumber()) &&
                            !(group.getGroupNumber() == 0 && (ast.getProperties().hasMatchBoundaryAssertions() || ast.getOptions().isMustAdvance()))) {
                group.clearGroupNumber();
            }
        }
    }

    private static final class ClearRegisteredCaptureGroupsVisitor extends DepthFirstTraversalRegexASTVisitor {

        private final RegexAST ast;

        private ClearRegisteredCaptureGroupsVisitor(RegexAST ast) {
            this.ast = ast;
        }

        public void clear(RegexASTNode root) {
            run(root);
        }

        @Override
        protected void visit(Group group) {
            if (group.isCapturing()) {
                ast.clearRegisteredCaptureGroups(group.getGroupNumber());
            }
        }
    }
}
