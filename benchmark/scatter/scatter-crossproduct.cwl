cwlVersion: v1.2
class: Workflow

# Scatter over two arrays with `scatterMethod: flat_crossproduct`: the step runs
# once for every (x, y) pair and the results are gathered into a single flat
# array. This is the cross-product pairing rule (contrast dotproduct, which
# zips). Fleur does not yet honor scatter at all.
requirements:
  InlineJavascriptRequirement: {}
  ScatterFeatureRequirement: {}

inputs:
  a:
    type: int[]
  b:
    type: int[]

outputs:
  products:
    type: int[]
    outputSource: mul/out

steps:
  mul:
    scatter: [x, y]
    scatterMethod: flat_crossproduct
    in:
      x: a
      y: b
    out: [out]
    run:
      class: ExpressionTool
      inputs:
        x: { type: int }
        y: { type: int }
      outputs:
        out: { type: int }
      expression: "${ return {out: inputs.x * inputs.y}; }"
