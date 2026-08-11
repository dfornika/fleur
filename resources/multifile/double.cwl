cwlVersion: v1.2
class: ExpressionTool
requirements:
  InlineJavascriptRequirement: {}
inputs:
  n:
    type: int
outputs:
  out:
    type: int
expression: "${ return {out: inputs.n * 2}; }"
