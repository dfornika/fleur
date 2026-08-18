cwlVersion: v1.2
class: CommandLineTool

# CommandLineTool `arguments` with a constant token and a `valueFrom` expression,
# echoed to a captured stdout File. Exercises the arguments list, valueFrom
# evaluation in the command-line builder, and stdout capture.
requirements:
  InlineJavascriptRequirement: {}

baseCommand: echo

arguments:
  - "hello"
  - valueFrom: $(inputs.name)

inputs:
  name:
    type: string

stdout: greeting.txt

outputs:
  out:
    type: File
    outputBinding:
      glob: greeting.txt
