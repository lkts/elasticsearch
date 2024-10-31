/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.mapper;

import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class SyntheticSourceDocumentParserListener implements DocumentParserListener {
    private final MappingLookup mappingLookup;
    private final List<IgnoredSourceFieldMapper.NameValue> valuesToStore;

    // TODO support switching documents
    private LuceneDocument document;

    private State state;

    public SyntheticSourceDocumentParserListener(MappingLookup mappingLookup, LuceneDocument document) {
        this.mappingLookup = mappingLookup;

        this.valuesToStore = new ArrayList<>();

        this.document = document;
        this.state = new Watching(MapperService.SINGLE_MAPPING_NAME, mappingLookup.getMapping().getRoot());
    }

    @Override
    public void consume(Token token) throws IOException {
        if (token == null) {
            return;
        }

        this.state = state.consume(token);
    }

    public List<IgnoredSourceFieldMapper.NameValue> getValuesToStore() {
        return valuesToStore;
    }

    interface State {
        State consume(Token token) throws IOException;
    }

    class Storing implements State {
        private final Token startingToken;
        private final String name;
        private final XContentBuilder data;
        private int depth;

        Storing(Token startingToken, String name) throws IOException {
            this.startingToken = startingToken;
            this.name = name;
            // TODO use actual value from initial parser (add a "document start" token with metadata?)
            this.data = XContentBuilder.builder(XContentType.JSON.xContent());
            this.depth = 0;

            consume(startingToken);
        }

        public State consume(Token token) throws IOException {
            switch (token) {
                case Token.StartObject ignored -> {
                    data.startObject();
                    if (startingToken instanceof Token.StartObject) {
                        depth += 1;
                    }
                }
                case Token.EndObject ignored -> {
                    data.endObject();

                    if (startingToken instanceof Token.StartObject) {
                        depth -= 1;
                        if (depth == 0) {
                            // TODO
                            valuesToStore.add(
                                new IgnoredSourceFieldMapper.NameValue(name, 0, XContentDataHelper.encodeXContentBuilder(data), document)
                            );
                            return new Watching(null, null);
                        }

                    }
                }
                case Token.StartArray ignored -> {
                    data.startArray();
                    if (startingToken instanceof Token.StartArray) {
                        depth += 1;
                    }
                }
                case Token.EndArray ignored -> {
                    data.endArray();

                    if (startingToken instanceof Token.StartArray) {
                        depth -= 1;
                        if (depth == 0) {
                            return new Watching(null, null);
                        }
                    }
                }
                case Token.FieldName fieldName -> data.field(fieldName.name());
                case Token.StringValue stringValue -> data.value(stringValue.value());
                case Token.BooleanValue booleanValue -> data.value(booleanValue.value());
                case Token.IntValue intValue -> data.value(intValue.value());
            }

            return this;
        }
    }

    class Watching implements State {
        // TODO this is wrong, it should contain parent names too
        private String currentName;
        private ObjectMapper parentMapper;
        private Mapper currentMapper;

        Watching(String currentName, Mapper currentMapper) {
            this.currentName = currentName;
            this.parentMapper = (ObjectMapper) currentMapper;
            this.currentMapper = currentMapper;
        }

        public State consume(Token token) throws IOException {
            switch (token) {
                case Token.StartObject startObject -> {
                    if (currentMapper instanceof ObjectMapper om && om.isEnabled() == false) {
                        return new Storing(startObject, currentName);
                    }
                }
                case Token.EndObject endObject -> {
                }
                case Token.StartArray startArray -> {
                }
                case Token.EndArray endArray -> {
                }
                case Token.FieldName fieldName -> {
                    currentName = fieldName.name();
                    var newCurrent = parentMapper != null ? parentMapper.getMapper(currentName) : null;
                    if (currentMapper instanceof ObjectMapper om) {
                        parentMapper = om;
                    }
                    currentMapper = newCurrent;
                }

                case Token.StringValue stringValue -> {
                }
                case Token.BooleanValue booleanValue -> {
                }
                case Token.IntValue intValue -> {
                }
            }

            return this;
        }
    }
}
