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

package com.palantir.baseline.refaster;

import org.junit.Test;

public class AssertjIsTrueTest {

    @Test
    public void test() {
        RefasterTestHelper
                .forRefactoring(
                        AssertjIsTrue.class,
                        AssertjIsTrueWithDescription.class)
                .withInputLines(
                        "Test",
                        "import static org.assertj.core.api.Assertions.assertThat;",
                        "public class Test {",
                        "  void f(boolean bool) {",
                        "    assertThat(bool).isEqualTo(true);",
                        "    assertThat(bool).isEqualTo(Boolean.TRUE);",
                        "    assertThat(true).isEqualTo(bool);",
                        "    assertThat(Boolean.TRUE).isEqualTo(bool);",
                        "    assertThat(bool).describedAs(\"desc\").isEqualTo(true);",
                        "    assertThat(bool).describedAs(\"desc\").isEqualTo(Boolean.TRUE);",
                        "    assertThat(true).describedAs(\"desc\").isEqualTo(bool);",
                        "    assertThat(Boolean.TRUE).describedAs(\"desc\").isEqualTo(bool);",
                        "  }",
                        "}")
                .hasOutputLines(
                        "import static org.assertj.core.api.Assertions.assertThat;",
                        "public class Test {",
                        "  void f(boolean bool) {",
                        "    assertThat(bool).isTrue();",
                        "    assertThat(bool).isTrue();",
                        "    assertThat(bool).isTrue();",
                        "    assertThat(bool).isTrue();",
                        "    assertThat(bool).describedAs(\"desc\").isTrue();",
                        "    assertThat(bool).describedAs(\"desc\").isTrue();",
                        "    assertThat(bool).describedAs(\"desc\").isTrue();",
                        "    assertThat(bool).describedAs(\"desc\").isTrue();",
                        "  }",
                        "}");
    }
}
