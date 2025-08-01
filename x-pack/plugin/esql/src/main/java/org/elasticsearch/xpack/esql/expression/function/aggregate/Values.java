/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.aggregate;

import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.compute.aggregation.AggregatorFunctionSupplier;
import org.elasticsearch.compute.aggregation.ValuesBooleanAggregatorFunctionSupplier;
import org.elasticsearch.compute.aggregation.ValuesBytesRefAggregatorFunctionSupplier;
import org.elasticsearch.compute.aggregation.ValuesDoubleAggregatorFunctionSupplier;
import org.elasticsearch.compute.aggregation.ValuesIntAggregatorFunctionSupplier;
import org.elasticsearch.compute.aggregation.ValuesLongAggregatorFunctionSupplier;
import org.elasticsearch.xpack.esql.EsqlIllegalArgumentException;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.TypeResolutions;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.Example;
import org.elasticsearch.xpack.esql.expression.function.FunctionAppliesTo;
import org.elasticsearch.xpack.esql.expression.function.FunctionAppliesToLifecycle;
import org.elasticsearch.xpack.esql.expression.function.FunctionInfo;
import org.elasticsearch.xpack.esql.expression.function.FunctionType;
import org.elasticsearch.xpack.esql.expression.function.Param;
import org.elasticsearch.xpack.esql.planner.ToAggregator;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static java.util.Collections.emptyList;
import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.ParamOrdinal.DEFAULT;

public class Values extends AggregateFunction implements ToAggregator {
    public static final NamedWriteableRegistry.Entry ENTRY = new NamedWriteableRegistry.Entry(Expression.class, "Values", Values::new);

    private static final Map<DataType, Supplier<AggregatorFunctionSupplier>> SUPPLIERS = Map.ofEntries(
        Map.entry(DataType.INTEGER, ValuesIntAggregatorFunctionSupplier::new),
        Map.entry(DataType.LONG, ValuesLongAggregatorFunctionSupplier::new),
        Map.entry(DataType.UNSIGNED_LONG, ValuesLongAggregatorFunctionSupplier::new),
        Map.entry(DataType.DATETIME, ValuesLongAggregatorFunctionSupplier::new),
        Map.entry(DataType.DATE_NANOS, ValuesLongAggregatorFunctionSupplier::new),
        Map.entry(DataType.DOUBLE, ValuesDoubleAggregatorFunctionSupplier::new),
        Map.entry(DataType.KEYWORD, ValuesBytesRefAggregatorFunctionSupplier::new),
        Map.entry(DataType.TEXT, ValuesBytesRefAggregatorFunctionSupplier::new),
        Map.entry(DataType.IP, ValuesBytesRefAggregatorFunctionSupplier::new),
        Map.entry(DataType.VERSION, ValuesBytesRefAggregatorFunctionSupplier::new),
        Map.entry(DataType.GEO_POINT, ValuesBytesRefAggregatorFunctionSupplier::new),
        Map.entry(DataType.CARTESIAN_POINT, ValuesBytesRefAggregatorFunctionSupplier::new),
        Map.entry(DataType.GEO_SHAPE, ValuesBytesRefAggregatorFunctionSupplier::new),
        Map.entry(DataType.CARTESIAN_SHAPE, ValuesBytesRefAggregatorFunctionSupplier::new),
        Map.entry(DataType.BOOLEAN, ValuesBooleanAggregatorFunctionSupplier::new)
    );

    @FunctionInfo(
        returnType = {
            "boolean",
            "cartesian_point",
            "cartesian_shape",
            "date",
            "date_nanos",
            "double",
            "geo_point",
            "geo_shape",
            "integer",
            "ip",
            "keyword",
            "long",
            "unsigned_long",
            "version" },
        preview = true,
        appliesTo = { @FunctionAppliesTo(lifeCycle = FunctionAppliesToLifecycle.PREVIEW) },
        description = """
            Returns unique values as a multivalued field. The order of the returned values isn’t guaranteed.
            If you need the values returned in order use
            [`MV_SORT`](/reference/query-languages/esql/functions-operators/mv-functions.md#esql-mv_sort).""",
        appendix = """
            ::::{tip}
            Use [`TOP`](/reference/query-languages/esql/functions-operators/aggregation-functions.md#esql-top)
            if you need to keep repeated values.
            ::::
            ::::{warning}
            This can use a significant amount of memory and ES|QL doesn’t yet
            grow aggregations beyond memory. So this aggregation will work until
            it is used to collect more values than can fit into memory. Once it
            collects too many values it will fail the query with
            a [Circuit Breaker Error](docs-content://troubleshoot/elasticsearch/circuit-breaker-errors.md).
            ::::""",
        type = FunctionType.AGGREGATE,
        examples = @Example(file = "string", tag = "values-grouped")
    )
    public Values(
        Source source,
        @Param(
            name = "field",
            type = {
                "boolean",
                "cartesian_point",
                "cartesian_shape",
                "date",
                "date_nanos",
                "double",
                "geo_point",
                "geo_shape",
                "integer",
                "ip",
                "keyword",
                "long",
                "unsigned_long",
                "text",
                "version" }
        ) Expression v
    ) {
        this(source, v, Literal.TRUE);
    }

    public Values(Source source, Expression field, Expression filter) {
        super(source, field, filter, emptyList());
    }

    private Values(StreamInput in) throws IOException {
        super(in);
    }

    @Override
    public String getWriteableName() {
        return ENTRY.name;
    }

    @Override
    protected NodeInfo<Values> info() {
        return NodeInfo.create(this, Values::new, field(), filter());
    }

    @Override
    public Values replaceChildren(List<Expression> newChildren) {
        return new Values(source(), newChildren.get(0), newChildren.get(1));
    }

    @Override
    public Values withFilter(Expression filter) {
        return new Values(source(), field(), filter);
    }

    @Override
    public DataType dataType() {
        return field().dataType().noText();
    }

    @Override
    protected TypeResolution resolveType() {
        return TypeResolutions.isRepresentableExceptCounters(field(), sourceText(), DEFAULT);
    }

    @Override
    public AggregatorFunctionSupplier supplier() {
        DataType type = field().dataType();
        if (SUPPLIERS.containsKey(type) == false) {
            // If the type checking did its job, this should never happen
            throw EsqlIllegalArgumentException.illegalDataType(type);
        }
        return SUPPLIERS.get(type).get();
    }
}
