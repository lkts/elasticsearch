% This is generated by ESQL's AbstractFunctionTestCase. Do not edit it. See ../README.md for how to regenerate it.

### LEFT
Returns the substring that extracts *length* chars from *string* starting from the left.

```esql
FROM employees
| KEEP last_name
| EVAL left = LEFT(last_name, 3)
```
