/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.services.ibmwatsonx.response;

import org.apache.http.HttpResponse;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.core.inference.results.TextEmbeddingFloatResults;
import org.elasticsearch.xpack.inference.external.http.HttpResult;
import org.elasticsearch.xpack.inference.external.request.Request;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;

public class IbmWatsonxEmbeddingsResponseEntityTests extends ESTestCase {
    public void testFromResponse_CreatesResultsForASingleItem() throws IOException {
        String responseJson = """
            {
                "results": [
                    {
                        "embedding": [
                            -0.00606332,
                            0.058092743
                        ],
                        "input": "abc"
                    }
                ]
            }
            """;

        TextEmbeddingFloatResults parsedResults = IbmWatsonxEmbeddingsResponseEntity.fromResponse(
            mock(Request.class),
            new HttpResult(mock(HttpResponse.class), responseJson.getBytes(StandardCharsets.UTF_8))
        );

        assertThat(parsedResults.embeddings(), is(List.of(TextEmbeddingFloatResults.Embedding.of(List.of(-0.00606332F, 0.058092743F)))));
    }

    public void testFromResponse_CreatesResultsForMultipleItems() throws IOException {
        String responseJson = """
            {
                "results": [
                    {
                        "embedding": [
                            -0.00606332,
                            0.058092743
                        ],
                        "input": "abc"
                    },
                      {
                        "embedding": [
                             0.030681048,
                             0.01714732
                        ],
                        "input": "efg"
                    }
                ]
            }
            """;

        TextEmbeddingFloatResults parsedResults = IbmWatsonxEmbeddingsResponseEntity.fromResponse(
            mock(Request.class),
            new HttpResult(mock(HttpResponse.class), responseJson.getBytes(StandardCharsets.UTF_8))
        );

        assertThat(
            parsedResults.embeddings(),
            is(
                List.of(
                    TextEmbeddingFloatResults.Embedding.of(List.of(-0.00606332F, 0.058092743F)),
                    TextEmbeddingFloatResults.Embedding.of(List.of(0.030681048F, 0.01714732F))
                )
            )
        );
    }

    public void testFromResponse_FailsWhenEmbeddingsFieldIsNotPresent() {
        String responseJson = """
            {
                "non_results": [
                    {
                        "embedding": [
                            -0.00606332,
                            0.058092743
                        ],
                        "input": "abc"
                    },
                      {
                        "embedding": [
                             0.030681048,
                             0.01714732
                        ],
                        "input": "efg"
                    }
                ]
            }
            """;

        var thrownException = expectThrows(
            IllegalStateException.class,
            () -> IbmWatsonxEmbeddingsResponseEntity.fromResponse(
                mock(Request.class),
                new HttpResult(mock(HttpResponse.class), responseJson.getBytes(StandardCharsets.UTF_8))
            )
        );

        assertThat(thrownException.getMessage(), is("Failed to find required field [results] in IBM watsonx embeddings response"));
    }
}
