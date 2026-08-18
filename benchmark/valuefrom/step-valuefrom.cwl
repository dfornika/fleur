cwlVersion: v1.2
class: Workflow

# A workflow step whose input is derived by `valueFrom` rather than wired
# straight from a source. `valueFrom` on a step input sees `self` (the source
# value) and `inputs` (the step's other inputs); here it adds a constant offset
# before the step runs. Exercises step-level valueFrom evaluation.
requirements:
  InlineJavascriptRequirement: {}
  StepInputExpressionRequirement: {}

inputs:
  x:
    type: int

outputs:
  result:
    type: int
    outputSource: shift/out

steps:
  shift:
    in:
      n:
        source: x
        valueFrom: $(self + 100)
    out: [out]
    run:
      class: ExpressionTool
      inputs:
        n: { type: int }
      outputs:
        out: { type: int }
      expression: "${ return {out: inputs.n}; }"
