cwlVersion: v1.2
class: Workflow

# Per-element `valueFrom` on a SCATTERED input. Per the CWL spec, a scattered
# input's `valueFrom` is evaluated once per scatter job with `self` bound to that
# element, so `$(self * 10)` should turn [1,2,3] into [10,20,30]. Fleur applies a
# step-input `valueFrom` once to the whole array before scattering, so this does
# not work yet.
requirements:
  InlineJavascriptRequirement: {}
  ScatterFeatureRequirement: {}
  StepInputExpressionRequirement: {}

inputs:
  numbers:
    type: int[]

outputs:
  out:
    type: int[]
    outputSource: ten/out

steps:
  ten:
    scatter: n
    in:
      n:
        source: numbers
        valueFrom: $(self * 10)
    out: [out]
    run:
      class: ExpressionTool
      inputs:
        n: { type: int }
      outputs:
        out: { type: int }
      expression: "${ return {out: inputs.n}; }"
