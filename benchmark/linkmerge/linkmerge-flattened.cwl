cwlVersion: v1.2
class: Workflow

# A step input that draws from MULTIPLE sources merged into one array via
# `linkMerge: merge_flattened` (CWL Workflow multi-source inputs). Two upstream
# steps each emit an int[]; the collector receives them flattened into a single
# int[] and sums it. Fleur does not yet implement multi-source `linkMerge`.
requirements:
  InlineJavascriptRequirement: {}
  MultipleInputFeatureRequirement: {}

inputs:
  a:
    type: int
  b:
    type: int

outputs:
  total:
    type: int
    outputSource: collect/out

steps:
  left:
    in:
      n: a
    out: [out]
    run:
      class: ExpressionTool
      inputs:
        n: { type: int }
      outputs:
        out: { type: int[] }
      expression: "${ return {out: [inputs.n, inputs.n + 1]}; }"

  right:
    in:
      n: b
    out: [out]
    run:
      class: ExpressionTool
      inputs:
        n: { type: int }
      outputs:
        out: { type: int[] }
      expression: "${ return {out: [inputs.n, inputs.n + 1]}; }"

  collect:
    in:
      nums:
        source: [left/out, right/out]
        linkMerge: merge_flattened
    out: [out]
    run:
      class: ExpressionTool
      inputs:
        nums: { type: int[] }
      outputs:
        out: { type: int }
      expression: "${ return {out: inputs.nums.reduce(function(a, b) { return a + b; }, 0)}; }"
