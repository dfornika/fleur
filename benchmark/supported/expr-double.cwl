cwlVersion: v1.2
class: ExpressionTool

# Baseline: a plain ExpressionTool Fleur fully supports. Present so the harness
# has a known-green regression case and so the runner itself is exercised.
requirements:
  InlineJavascriptRequirement: {}

inputs:
  x:
    type: int

outputs:
  out:
    type: int

expression: "${ return {out: inputs.x * 2}; }"
