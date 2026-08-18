cwlVersion: v1.2
class: Workflow

# A workflow whose step `run`s an INLINE sub-Workflow (SubworkflowFeature). File-
# referenced sub-workflows (run: other.cwl) already work; this probes the inline
# form, which Fleur's step runner does not accept yet ("Step :run must be a
# process map or a file path"). The inner workflow doubles-then-increments; the
# outer workflow just feeds its input in and surfaces the inner result.
requirements:
  InlineJavascriptRequirement: {}
  SubworkflowFeatureRequirement: {}

inputs:
  x:
    type: int

outputs:
  result:
    type: int
    outputSource: inner/result

steps:
  inner:
    in:
      x: x
    out: [result]
    run:
      class: Workflow
      inputs:
        x: { type: int }
      outputs:
        result:
          type: int
          outputSource: increment/out
      steps:
        double:
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
        increment:
          in:
            n: double/out
          out: [out]
          run:
            class: ExpressionTool
            inputs:
              n: { type: int }
            outputs:
              out: { type: int }
            expression: "${ return {out: inputs.n + 1}; }"
