/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.artifacts.transform;

import org.gradle.internal.Try;
import org.gradle.internal.operations.BuildOperationCategory;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.RunnableBuildOperation;

import javax.annotation.Nullable;

class TransformationOperation implements TransformationResult, RunnableBuildOperation {
    private final Transformation transformation;
    private final TransformationSubject subject;
    private final Transformation.TransformationContinuation<TransformationSubject> continuation;
    private Try<TransformationSubject> transformedSubject;

    TransformationOperation(Transformation transformation, TransformationSubject subject, ExecutionGraphDependenciesResolver dependenciesResolver) {
        this.transformation = transformation;
        this.subject = subject;
        this.continuation = transformation.prepareTransform(subject, dependenciesResolver, null);
    }

    @Override
    public void run(@Nullable BuildOperationContext context) {
        transformedSubject = continuation.invoke();
    }

    public boolean isExpensive() {
        return continuation.isExpensive();
    }

    @Override
    public BuildOperationDescriptor.Builder description() {
        String displayName = "Transform " + subject.getDisplayName() + " with " + transformation.getDisplayName();
        return BuildOperationDescriptor.displayName(displayName)
            .progressDisplayName(displayName)
            .operationType(BuildOperationCategory.UNCATEGORIZED);
    }

    @Override
    public Try<TransformationSubject> getTransformedSubject() {
        return transformedSubject;
    }
}
