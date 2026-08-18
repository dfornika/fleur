cwlVersion: v1.2
class: Workflow

# A diamond-shaped static DAG: one input fans out to two independent steps and
# fans back in to a third. Exercises Fleur's topological step ordering and
# output->input wiring on a non-linear (but still static) graph.
requirements:
  InlineJavascriptRequirement: {}

inputs:
  x:
    type: int

outputs:
  total:
    type: int
    outputSource: combine/out

steps:
  plus1:
    in:
      n: x
    out: [out]
    run:
      class: ExpressionTool
      inputs:
        n: { type: int }
      outputs:
        out: { type: int }
      expression: "${ return {out: inputs.n + 1}; }"

  times2:
    in:
      n: x
    out: [out]
    run:
      class: ExpressionTool
      inputs:
        n: { type: int }
      outputs:
        out: { type: int }
      expression: "${ return {out: inputs.n * 2}; }"

  combine:
    in:
      a: plus1/out
      b: times2/out
    out: [out]
    run:
      class: ExpressionTool
      inputs:
        a: { type: int }
        b: { type: int }
      outputs:
        out: { type: int }
      expression: "${ return {out: inputs.a + inputs.b}; }"
