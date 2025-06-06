/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.core.ilm;

import org.elasticsearch.cluster.ProjectState;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.ProjectMetadata;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.xpack.core.ilm.ClusterStateWaitStep.Result;
import org.elasticsearch.xpack.core.ilm.Step.StepKey;

import static org.elasticsearch.xpack.core.ilm.ShrinkIndexNameSupplier.SHRUNKEN_INDEX_PREFIX;
import static org.hamcrest.Matchers.equalTo;

public class ShrunkenIndexCheckStepTests extends AbstractStepTestCase<ShrunkenIndexCheckStep> {

    @Override
    public ShrunkenIndexCheckStep createRandomInstance() {
        StepKey stepKey = randomStepKey();
        StepKey nextStepKey = randomStepKey();
        String shrunkIndexPrefix = randomAlphaOfLength(10);
        return new ShrunkenIndexCheckStep(stepKey, nextStepKey);
    }

    @Override
    public ShrunkenIndexCheckStep mutateInstance(ShrunkenIndexCheckStep instance) {
        StepKey key = instance.getKey();
        StepKey nextKey = instance.getNextStepKey();
        switch (between(0, 1)) {
            case 0 -> key = new StepKey(key.phase(), key.action(), key.name() + randomAlphaOfLength(5));
            case 1 -> nextKey = new StepKey(nextKey.phase(), nextKey.action(), nextKey.name() + randomAlphaOfLength(5));
            default -> throw new AssertionError("Illegal randomisation branch");
        }
        return new ShrunkenIndexCheckStep(key, nextKey);
    }

    @Override
    public ShrunkenIndexCheckStep copyInstance(ShrunkenIndexCheckStep instance) {
        return new ShrunkenIndexCheckStep(instance.getKey(), instance.getNextStepKey());
    }

    public void testConditionMet() {
        ShrunkenIndexCheckStep step = createRandomInstance();
        String sourceIndex = randomAlphaOfLengthBetween(1, 10);
        IndexMetadata indexMetadata = IndexMetadata.builder(SHRUNKEN_INDEX_PREFIX + sourceIndex)
            .settings(settings(IndexVersion.current()).put(IndexMetadata.INDEX_RESIZE_SOURCE_NAME_KEY, sourceIndex))
            .numberOfShards(1)
            .numberOfReplicas(0)
            .build();
        ProjectState state = projectStateFromProject(ProjectMetadata.builder(randomUniqueProjectId()).put(indexMetadata, false));

        Result result = step.isConditionMet(indexMetadata.getIndex(), state);
        assertTrue(result.complete());
        assertNull(result.informationContext());
    }

    public void testConditionNotMetBecauseNotSameShrunkenIndex() {
        ShrunkenIndexCheckStep step = createRandomInstance();
        String sourceIndex = randomAlphaOfLengthBetween(1, 10);
        IndexMetadata shrinkIndexMetadata = IndexMetadata.builder(sourceIndex + "hello")
            .settings(settings(IndexVersion.current()).put(IndexMetadata.INDEX_RESIZE_SOURCE_NAME_KEY, sourceIndex))
            .numberOfShards(1)
            .numberOfReplicas(0)
            .build();
        ProjectState state = projectStateFromProject(ProjectMetadata.builder(randomUniqueProjectId()).put(shrinkIndexMetadata, false));
        Result result = step.isConditionMet(shrinkIndexMetadata.getIndex(), state);
        assertFalse(result.complete());
        assertEquals(new ShrunkenIndexCheckStep.Info(sourceIndex), result.informationContext());
    }

    public void testConditionNotMetBecauseSourceIndexExists() {
        ShrunkenIndexCheckStep step = createRandomInstance();
        String sourceIndex = randomAlphaOfLengthBetween(1, 10);
        IndexMetadata originalIndexMetadata = IndexMetadata.builder(sourceIndex)
            .settings(settings(IndexVersion.current()))
            .numberOfShards(100)
            .numberOfReplicas(0)
            .build();
        IndexMetadata shrinkIndexMetadata = IndexMetadata.builder(SHRUNKEN_INDEX_PREFIX + sourceIndex)
            .settings(settings(IndexVersion.current()).put(IndexMetadata.INDEX_RESIZE_SOURCE_NAME_KEY, sourceIndex))
            .numberOfShards(1)
            .numberOfReplicas(0)
            .build();
        ProjectState state = projectStateFromProject(
            ProjectMetadata.builder(randomUniqueProjectId()).put(originalIndexMetadata, false).put(shrinkIndexMetadata, false)
        );
        Result result = step.isConditionMet(shrinkIndexMetadata.getIndex(), state);
        assertFalse(result.complete());
        assertEquals(new ShrunkenIndexCheckStep.Info(sourceIndex), result.informationContext());
    }

    public void testIllegalState() {
        ShrunkenIndexCheckStep step = createRandomInstance();
        IndexMetadata indexMetadata = IndexMetadata.builder(randomAlphaOfLength(5))
            .settings(settings(IndexVersion.current()))
            .numberOfShards(1)
            .numberOfReplicas(0)
            .build();
        ProjectState state = projectStateFromProject(ProjectMetadata.builder(randomUniqueProjectId()).put(indexMetadata, false));
        IllegalStateException exception = expectThrows(
            IllegalStateException.class,
            () -> step.isConditionMet(indexMetadata.getIndex(), state)
        );
        assertThat(
            exception.getMessage(),
            equalTo("step[is-shrunken-index] is checking an un-shrunken index[" + indexMetadata.getIndex().getName() + "]")
        );
    }
}
