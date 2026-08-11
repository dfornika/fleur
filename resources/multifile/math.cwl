cwlVersion: v1.2
class: Workflow
# Steps reference their processes by file (run: <file>.cwl), exercising
# multi-file workflow resolution.
inputs:
  x:
    type: int
outputs:
  result:
    type: int
    outputSource: increment/out
steps:
  double:
    run: double.cwl
    in:
      n: x
    out: [out]
  increment:
    run: increment.cwl
    in:
      n: double/out
    out: [out]
