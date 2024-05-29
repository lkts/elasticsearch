/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.mapper;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

public class SyntheticSourceTests {
    public interface SyntheticSourceContract<V> {
        @Property
        default void singleNonNullValueIsPresentInSyntheticSource(@ForAll("mapping") Object mapping, @ForAll("value") V value) {

        }

        // arrays
        // nulls in different variations, empty arrays
        // roundtrip

        // malformed should go into a separate interface (!!!)

        // fallback support should go into separate interface but can share some stuff with this one

        @Provide
        Arbitrary<Object> mapping();

        Arbitrary<V> fieldValue();
    }

    class BooleanFieldMapperSyntheticSourceTests implements SyntheticSourceContract<Boolean> {

        @Override
        public Arbitrary<Object> mapping() {
            return null;
        }

        @Override
        public Arbitrary<Boolean> fieldValue() {
            return Arbitraries.of(true, false);
        }
    }
}
