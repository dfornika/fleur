cwlVersion: v1.2
class: CommandLineTool

# stdin redirection: feed an input File to the tool's stdin (via a `$(...)`
# expression) and capture stdout. Exercises stdin wiring plus expression-valued
# redirection.
requirements:
  InlineJavascriptRequirement: {}

baseCommand: cat

inputs:
  f:
    type: File

stdin: $(inputs.f.path)
stdout: out.txt

outputs:
  out:
    type: File
    outputBinding:
      glob: out.txt
