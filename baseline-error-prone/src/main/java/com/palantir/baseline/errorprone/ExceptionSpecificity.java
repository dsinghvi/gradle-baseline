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
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.matchers.Matchers;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.tools.javac.code.Type;

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

    @Override
    public Description matchTry(TryTree tree, VisitorState state) {
        for (CatchTree catchTree : tree.getCatches()) {
            Tree catchTypeTree = catchTree.getParameter().getType();
            Type catchType = ASTHelpers.getType(catchTypeTree);
            // Don't match union types for now e.g. 'catch (RuntimeException | Error e)'
            // It's not worth the complexity at this point.
            if (catchType == null || catchType.isUnion()) {
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
                    return buildDescription(catchTypeTree)
                            .addFix(SuggestedFix.builder()
                                    .replace(catchTypeTree,
                                            isThrowable ? "RuntimeException | Error" : "RuntimeException")
                                    .build())
                            .build();
                }
                return Description.NO_MATCH;
            }
        }
        return Description.NO_MATCH;
    }

    private static boolean throwsCheckedExceptions(TryTree tree, VisitorState state) {
        return throwsCheckedExceptions(tree.getBlock(), state)
                || tree.getResources().stream().anyMatch(resource -> throwsCheckedExceptions(resource, state));
    }

    private static boolean throwsCheckedExceptions(Tree tree, VisitorState state) {
        return !MoreASTHelpers.getThrownCheckedExceptions(tree, state).isEmpty();
    }

}
