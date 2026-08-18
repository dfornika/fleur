cwlVersion: v1.2
class: Workflow

# Scatter a single-input step over an array, gathering the results back into an
# array (CWL Workflow, "scatter" on one parameter). Fleur does not yet honor
# `scatter`, so today it feeds the whole array into the scalar step and returns
# a wrong result — this probe documents the gap and becomes a real test once
# scatter lands.
requirements:
  InlineJavascriptRequirement: {}
  ScatterFeatureRequirement: {}

inputs:
  numbers:
    type: int[]

outputs:
  doubled:
    type: int[]
    outputSource: double/out

steps:
  double:
    scatter: n
    in:
      n: numbers
    out: [out]
    run:
      class: ExpressionTool
      inputs:
        n: { type: int }
      outputs:
        out: { type: int }
      expression: "${ return {out: inputs.n * 2}; }"
