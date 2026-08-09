cwlVersion: v1.2
class: ExpressionTool
requirements:
  InlineJavascriptRequirement: {}
inputs:
  number_string:
    type: string
outputs:
  parsed:
    type: int
expression: "${ return {parsed: parseInt(inputs.number_string)}; }"
