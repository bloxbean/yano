# `cddl-yano-subset-v1`

This file freezes the declarative authoring subset compiled to
`yano-cbor-schema-ir-v1`. The source is a build-time input only; canonical IR
bytes in authenticated-map genesis determine consensus.

## Grammar

```text
document      = rule+
rule          = identifier "=" type
type          = primary-control ("/" primary-control)*
primary-control = primary control*
primary       = identifier | primitive | integer | text-literal | byte-literal
              | "true" | "false" | "null" | map | array | "(" type ")"
              | integer ".." integer
primitive     = "uint" | "nint" | "int" | "tstr" | "bstr" | "bool"
map           = "{" [field ("," field)* [","]] "}"
field         = ["?"] (identifier | text-literal) ":" type
array         = "[" [term ("," term)* [","]] "]"
term          = ["?" | nonnegative-integer "*" nonnegative-integer] type
control       = (".ge" | ".gt" | ".le" | ".lt" | ".eq") integer
              | ".size" (nonnegative-integer
                | "(" nonnegative-integer ".." nonnegative-integer ")")
```

Semicolon comments run to end of line. Identifiers match
`[A-Za-z_][A-Za-z0-9_-]{0,63}`. Integers use canonical base-10 spelling with no
leading zero or plus sign. Text literals support `\"`, `\\`, `\n`, `\r`, `\t`,
and `\uXXXX`; byte literals use `h'00ff'` syntax.

Maps are exact and text-keyed: undeclared keys are rejected. `?` makes a map
field or array term optional. Array repetition always has explicit inclusive
bounds. Named rules are expanded before IR generation; unknown and recursive
references are rejected.

`.size` applies only to `tstr` and `bstr` and counts encoded bytes. Numeric
controls apply only to native CBOR integers. Choices and map fields are sorted
canonically in IR, so source declaration order does not affect the result.

## Closed-world exclusions

The subset rejects unbounded occurrence, generics, sockets, group inclusion,
external files or references, tags, floats, `any`, regular expressions, and all
controls not listed above. There is no implementation-defined extension point.

## Frozen limits

- source: 65,536 UTF-8 bytes, 128 rules, 16,384 lexer tokens;
- IR: 65,536 bytes, 2,048 nodes, nesting depth 32;
- choice: 32 options; exact map: 256 fields; array: 256 terms;
- array occurrence maximum: 65,536;
- runtime evaluation: 262,144 steps, in addition to authenticated-map value
  byte and deterministic-CBOR structural limits.

The native integer domain is `[-2^64, 2^64-1]`, matching untagged CBOR major
types 0 and 1. Text and byte size bounds cannot exceed the authenticated-map
maximum value size.
