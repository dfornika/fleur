cwlVersion: v1.2
class: Workflow

# A multi-source step input merged with `linkMerge: merge_nested`: each source
# contributes one element to an outer array (so N int sources -> int[] of length
# N), as opposed to merge_flattened which concatenates. The collector sums the
# nested array. Fleur does not yet implement multi-source linkMerge.
requirements:
  InlineJavascriptRequirement: {}
  MultipleInputFeatureRequirement: {}

inputs:
  a:
    type: int
  b:
    type: int
  c:
    type: int

outputs:
  total:
    type: int
    outputSource: collect/out

steps:
  collect:
    in:
      nums:
        source: [a, b, c]
        linkMerge: merge_nested
    out: [out]
    run:
      class: ExpressionTool
      inputs:
        nums: { type: int[] }
      outputs:
        out: { type: int }
      expression: "${ return {out: inputs.nums.reduce(function(a, b) { return a + b; }, 0)}; }"
