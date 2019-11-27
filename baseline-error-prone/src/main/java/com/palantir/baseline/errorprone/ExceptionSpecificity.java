/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
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
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.fixes.SuggestedFixes;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.matchers.Matchers;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@AutoService(BugChecker.class)
@BugPattern(
        name = "ExceptionSpecificity",
        link = "https://github.com/palantir/gradle-baseline#baseline-error-prone-checks",
        linkType = BugPattern.LinkType.CUSTOM,
        severity = BugPattern.SeverityLevel.SUGGESTION,
        summary = "Prefer more specific catch types than Exception and Throwable. When methods are updated to throw "
                + "new checked exceptions they expect callers to handle failure types explicitly. Catching broad "
                + "types defeats the type system.")
public final class ExceptionSpecificity extends BugChecker implements BugChecker.TryTreeMatcher {

    private static final Matcher<Tree> THROWABLE = Matchers.isSameType(Throwable.class);
    private static final Matcher<Tree> EXCEPTION = Matchers.isSameType(Exception.class);

    private static final ImmutableList<String> THROWABLE_REPLACEMENTS =
            ImmutableList.of(RuntimeException.class.getName(), Error.class.getName());
    private static final ImmutableList<String> EXCEPTION_REPLACEMENTS =
            ImmutableList.of(RuntimeException.class.getName());

    @Override
    public Description matchTry(TryTree tree, VisitorState state) {
        List<Type> encounteredTypes = new ArrayList<>();
        for (CatchTree catchTree : tree.getCatches()) {
            Tree catchTypeTree = catchTree.getParameter().getType();
            Type catchType = ASTHelpers.getType(catchTypeTree);
            // Don't match union types for now e.g. 'catch (RuntimeException | Error e)'
            // It's not worth the complexity at this point.
            if (catchType == null) {
                continue;
            }
            if (catchType.isUnion()) {
                Type.UnionClassType unionType = (Type.UnionClassType) catchType;
                unionType.getAlternativeTypes().forEach(encounteredTypes::add);
                continue;
            }

            boolean isException = EXCEPTION.matches(catchTypeTree, state);
            boolean isThrowable = THROWABLE.matches(catchTypeTree, state);
            if (isException || isThrowable) {
                // Currently we only check that there are no checked exceptions. In a future change
                // we should apply the checked exceptions to our replacement when:
                // 1. Checked exceptions include neither Exception nor Throwable.
                // 2. We have implemented deduplication e.g. [IOException, FileNotFoundException] -> [IOException].
                // 3. There are fewer than some threshold of checked exceptions, perhaps three.
                if (!throwsCheckedExceptions(tree, state)) {
                    List<String> replacements = deduplicateCatchTypes(
                            isThrowable ? THROWABLE_REPLACEMENTS : EXCEPTION_REPLACEMENTS,
                            encounteredTypes,
                            state);
                    SuggestedFix.Builder fix = SuggestedFix.builder();
                    if (replacements.isEmpty()) {
                        fix.replace(catchTree, "");
                    } else {
                        fix.replace(catchTypeTree, replacements.stream()
                                .map(type -> SuggestedFixes.qualifyType(state, fix, type))
                                .collect(Collectors.joining(" | ")));
                    }
                    return buildDescription(catchTypeTree)
                            .addFix(fix.build())
                            .build();
                }
                return Description.NO_MATCH;
            }
            // mark the type as caught before continuing
            encounteredTypes.add(catchType);
        }
        return Description.NO_MATCH;
    }

    /** Caught types cannot be duplicated because code will not compile. */
    private static List<String> deduplicateCatchTypes(
            List<String> proposedReplacements,
            List<Type> caughtTypes,
            VisitorState state) {
        List<String> replacements = new ArrayList<>();
        for (String proposedReplacement : proposedReplacements) {
            Type replacementType = state.getTypeFromString(proposedReplacement);
            if (caughtTypes.stream()
                    .noneMatch(alreadyCaught -> state.getTypes().isSubtype(replacementType, alreadyCaught))) {
                replacements.add(proposedReplacement);
            }
        }
        return replacements;
    }

    private static boolean throwsCheckedExceptions(TryTree tree, VisitorState state) {
        return throwsCheckedExceptions(tree.getBlock(), state)
                || tree.getResources().stream().anyMatch(resource -> resourceThrowsCheckedExceptions(resource, state));
    }

    private static boolean throwsCheckedExceptions(Tree tree, VisitorState state) {
        return !MoreASTHelpers.getThrownCheckedExceptions(tree, state).isEmpty();
    }

    private static boolean resourceThrowsCheckedExceptions(Tree resource, VisitorState state) {
        if (throwsCheckedExceptions(resource, state)) {
            return true;
        }
        Type resourceType = ASTHelpers.getType(resource);
        if (resourceType == null) {
            return false;
        }
        Symbol.TypeSymbol symbol = resourceType.tsym;
        if (symbol instanceof Symbol.ClassSymbol) {
            return MoreASTHelpers.getCloseMethod((Symbol.ClassSymbol) symbol, state)
                    // Checks any exception is thrown, ideally this would only check for IOExceptions
                    .map(Symbol.MethodSymbol::getThrownTypes).map(types -> !types.isEmpty())
                    .orElse(false);
        }
        return false;
    }
}
