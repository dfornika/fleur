cwlVersion: v1.2
class: Workflow

# InlineJavascriptRequirement is declared once here and inherited by the steps.
requirements:
  InlineJavascriptRequirement: {}

inputs:
  x:
    type: int

outputs:
  result:
    type: int
    outputSource: increment/out

# Steps are listed in reverse dependency order on purpose: execution order is
# determined by the dependency graph, not by declaration order.
steps:
  increment:
    in:
      n: double/out
    out: [out]
    run:
      class: ExpressionTool
      inputs:
        n:
          type: int
      outputs:
        out:
          type: int
      expression: "${ return {out: inputs.n + 1}; }"

  double:
    in:
      n: x
    out: [out]
    run:
      class: ExpressionTool
      inputs:
        n:
          type: int
      outputs:
        out:
          type: int
      expression: "${ return {out: inputs.n * 2}; }"
