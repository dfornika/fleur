cwlVersion: v1.2
class: Workflow

# Scatter over TWO parameters in lock-step (`scatterMethod: dotproduct`),
# summing the i-th element of each array. Exercises multi-parameter scatter and
# the dotproduct pairing rule (as opposed to nested/flat cross-products).
requirements:
  InlineJavascriptRequirement: {}
  ScatterFeatureRequirement: {}

inputs:
  a:
    type: int[]
  b:
    type: int[]

outputs:
  sums:
    type: int[]
    outputSource: add/out

steps:
  add:
    scatter: [x, y]
    scatterMethod: dotproduct
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
      expression: "${ return {out: inputs.x + inputs.y}; }"
