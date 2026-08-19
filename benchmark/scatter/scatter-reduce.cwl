cwlVersion: v1.2
class: Workflow

# A realistic scatter-then-reduce shape: scatter a per-element transform over an
# array, then feed the gathered array into a reduce step. Exercises scatter
# output wiring into a downstream (non-scattered) step.
requirements:
  InlineJavascriptRequirement: {}
  ScatterFeatureRequirement: {}

inputs:
  numbers:
    type: int[]

outputs:
  total:
    type: int
    outputSource: sum/out

steps:
  double:
    scatter: n
    in:
      n: numbers
    out: [out]
    run:
      class: ExpressionTool
      inputs:
        n: { type: int }
      outputs:
        out: { type: int }
      expression: "${ return {out: inputs.n * 2}; }"

  sum:
    in:
      xs: double/out
    out: [out]
    run:
      class: ExpressionTool
      inputs:
        xs: { type: "int[]" }
      outputs:
        out: { type: int }
      expression: "${ return {out: inputs.xs.reduce(function(a, b) { return a + b; }, 0)}; }"
