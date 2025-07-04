% This is generated by ESQL's AbstractFunctionTestCase. Do not edit it. See ../README.md for how to regenerate it.

### MATCH OPERATOR `:`
Use the match operator (`:`) to perform a [match query](https://www.elastic.co/docs/reference/query-languages/query-dsl/query-dsl-match-query) on the specified field.
Using `:` is equivalent to using the `match` query in the Elasticsearch Query DSL.

The match operator is equivalent to the [match function](https://www.elastic.co/docs/reference/query-languages/esql/functions-operators/aggregation-functions#esql-match).

For using the function syntax, or adding [match query parameters](https://www.elastic.co/docs/reference/query-languages/query-dsl/query-dsl-match-query#match-field-params), you can use the
[match function](https://www.elastic.co/docs/reference/query-languages/esql/functions-operators/aggregation-functions#esql-match).

`:` returns true if the provided query matches the row.

```esql
FROM books
| WHERE author:"Faulkner"
```
