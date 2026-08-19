cwlVersion: v1.2
class: Workflow

# `linkMerge` on a workflow OUTPUT's `outputSource` (not a step input). Two steps
# each emit an int[]; the workflow output draws from both with
# `linkMerge: merge_flattened`, so the result should be one flat array
# [1,2,10,11]. Fleur applies linkMerge to step inputs but not yet to
# `outputSource`, so it currently produces a nested array instead.
requirements:
  InlineJavascriptRequirement: {}
  MultipleInputFeatureRequirement: {}

inputs:
  a:
    type: int
  b:
    type: int

outputs:
  merged:
    type: int[]
    outputSource: [left/out, right/out]
    linkMerge: merge_flattened

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
        out: { type: "int[]" }
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
        out: { type: "int[]" }
      expression: "${ return {out: [inputs.n, inputs.n + 1]}; }"
