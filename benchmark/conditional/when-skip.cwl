cwlVersion: v1.2
class: Workflow

# A conditional step guarded by `when`. When the guard evaluates false the step
# is skipped and its outputs are null (CWL Workflow conditional execution). This
# probe runs the workflow with the guard FALSE and expects a null output; Fleur
# does not yet honor `when`, so today it runs the step unconditionally and
# returns a value instead of null.
requirements:
  InlineJavascriptRequirement: {}

inputs:
  x:
    type: int
  run_it:
    type: boolean

outputs:
  result:
    type: ["null", int]
    outputSource: maybe_double/out

steps:
  maybe_double:
    when: $(inputs.run_it)
    in:
      n: x
      run_it: run_it
    out: [out]
    run:
      class: ExpressionTool
      inputs:
        n: { type: int }
        run_it: { type: boolean }
      outputs:
        out: { type: int }
      expression: "${ return {out: inputs.n * 2}; }"
