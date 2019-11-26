/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.baseline.errorprone;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.matchers.Matchers;
import com.google.errorprone.matchers.method.MethodMatchers;
import com.google.errorprone.util.ASTHelpers;
import com.google.errorprone.util.ErrorProneTokens;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.tools.javac.code.Type;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.UnionType;

@AutoService(BugChecker.class)
@BugPattern(
        name = "Interruption",
        link = "https://github.com/palantir/gradle-baseline#baseline-error-prone-checks",
        linkType = BugPattern.LinkType.CUSTOM,
        severity = BugPattern.SeverityLevel.WARNING,
        summary = "TODO: needs a novel to explain why this is important.")
public final class Interruption extends BugChecker implements BugChecker.TryTreeMatcher {

    private static final Matcher<ExpressionTree> THREAD_INTERRUPT = MethodMatchers.instanceMethod()
            .onDescendantOf(Thread.class.getName())
            .named("interrupt")
            .withParameters();

    private static final Matcher<Tree> CONTAINS_INTERRUPT =
            Matchers.contains(ExpressionTree.class, THREAD_INTERRUPT);

    @Override
    public Description matchTry(TryTree tree, VisitorState state) {
        for (CatchTree catchTree : tree.getCatches()) {
            Optional<InterruptedCatchType> maybeCatchType = getCatchType(
                    ASTHelpers.getType(catchTree.getParameter().getType()), state);
            if (!maybeCatchType.isPresent()) {
                return Description.NO_MATCH;
            }
            InterruptedCatchType catchType = maybeCatchType.get();
            if (!catchType.matches()) {
                continue;
            }
            if (CONTAINS_INTERRUPT.matches(catchTree, state)) {
                return Description.NO_MATCH;
            }
            if (ErrorProneTokens.getTokens(state.getSourceForNode(tree), state.context).stream()
                    .anyMatch(errorProneToken -> errorProneToken.comments().stream()
                            .anyMatch(comment -> comment.getText().contains("interruption reset")))) {
                return Description.NO_MATCH;
            }
            BlockTree blockTree = catchTree.getBlock();
            List<? extends StatementTree> statements = blockTree.getStatements();
            if (!statements.isEmpty()) {
                StatementTree lastStatement = statements.get(statements.size() - 1);
                if (lastStatement instanceof ThrowTree) {
                    return buildDescription(catchTree)
                            .addFix(SuggestedFix.builder()
                                    .prefixWith(statements.get(0), catchType.getFix(catchTree))
                                    .build())
                            .build();
                }
            }
            return Description.NO_MATCH;
        }
        return Description.NO_MATCH;
    }

    private static Optional<InterruptedCatchType> getCatchType(Type type, VisitorState state) {
        Type interruptedException = state.getTypeFromString(InterruptedException.class.getName());
        if (state.getTypes().isAssignable(type, interruptedException)) {
            return Optional.of(InterruptedCatchType.INTERRUPTED_SUBTYPE);
        }
        if (state.getTypes().isAssignable(interruptedException, type)) {
            if (type instanceof UnionType) {
                List<? extends TypeMirror> typeMirrors = ((UnionType) type).getAlternatives();
                if (!typeMirrors.stream().allMatch(Type.class::isInstance)) {
                    return Optional.empty();
                }
                List<Optional<InterruptedCatchType>> interruptedCatchTypes = typeMirrors.stream()
                        .map(Type.class::cast)
                        .map(unionType -> getCatchType(unionType, state))
                        .collect(ImmutableList.toImmutableList());
                if (!interruptedCatchTypes.stream().allMatch(Optional::isPresent)) {
                    return Optional.empty();
                }
                Set<InterruptedCatchType> uniqueTypes = interruptedCatchTypes.stream()
                        .map(Optional::get)
                        .collect(ImmutableSet.toImmutableSet());
                if (uniqueTypes.size() == 1) {
                    return Optional.of(Iterables.getOnlyElement(uniqueTypes));
                }
                return Optional.of(InterruptedCatchType.INTERRUPTED_SUPERTYPE);
            }
            return Optional.of(InterruptedCatchType.INTERRUPTED_SUPERTYPE);
        }
        return Optional.of(InterruptedCatchType.DOES_NOT_MATCH);
    }

    enum InterruptedCatchType {
        DOES_NOT_MATCH,
        /** Includes subtypes. */
        INTERRUPTED_SUBTYPE,
        /** Catching Exception will consume an InterruptedException. */
        INTERRUPTED_SUPERTYPE;

        boolean matches() {
            return this != DOES_NOT_MATCH;
        }

        String getFix(CatchTree catchTree) {
            switch (this) {
                case DOES_NOT_MATCH:
                    throw new IllegalStateException("Cannot fix code that does not match");
                case INTERRUPTED_SUBTYPE:
                    return "Thread.currentThread().interrupt();\n";
                case INTERRUPTED_SUPERTYPE:
                    return "if (" + catchTree.getParameter().getName().toString()
                            + " instanceof InterruptedException) { Thread.currentThread().interrupt(); }\n";
            }
            throw new IllegalStateException("Unknown InterruptedCatchType " + this);
        }
    }
}
