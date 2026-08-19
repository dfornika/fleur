cwlVersion: v1.2
class: CommandLineTool

# The `type: stdout` output shorthand: the output is a File collected from the
# captured stdout, without an explicit stdout filename or glob. Exercises whether
# the stdout-type shorthand is expanded/handled.
baseCommand: echo

arguments:
  - "shorthand stdout"

inputs: []

outputs:
  out:
    type: stdout
