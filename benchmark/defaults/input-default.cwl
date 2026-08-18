cwlVersion: v1.2
class: ExpressionTool

# An input with a `default`, invoked with the job omitting it. Per the spec the
# default should be substituted before the expression runs. Probes whether Fleur
# applies input defaults for ExpressionTool processes.
requirements:
  InlineJavascriptRequirement: {}

inputs:
  n:
    type: int
    default: 7

outputs:
  out:
    type: int

expression: "${ return {out: inputs.n * 3}; }"
