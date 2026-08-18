cwlVersion: v1.2
class: CommandLineTool

# Count lines in an input File and return the count as an int via
# `outputBinding.outputEval` reading the globbed stdout File's loaded contents.
# Exercises output loadContents + outputEval (turning a produced File back into a
# scalar). Classify by probing: it's supported only if Fleur loads output
# contents and runs outputEval.
requirements:
  InlineJavascriptRequirement: {}

baseCommand: [wc, -l]

inputs:
  f:
    type: File
    inputBinding:
      position: 1

stdout: count.txt

outputs:
  lines:
    type: int
    outputBinding:
      glob: count.txt
      loadContents: true
      outputEval: $(parseInt(self[0].contents))
