cwlVersion: v1.2
class: CommandLineTool

# A record-typed input whose fields carry their own `inputBinding`. Per the CWL
# command-line algorithm, the tool should recurse into the record's fields and
# place each bound field on the command line (here: echo x then y). Fleur's
# command-line builder does not yet handle record/object types, so the fields
# are not emitted.
baseCommand: echo

inputs:
  point:
    type:
      type: record
      fields:
        x:
          type: int
          inputBinding:
            position: 1
        y:
          type: int
          inputBinding:
            position: 2

stdout: out.txt

outputs:
  out:
    type: File
    outputBinding:
      glob: out.txt
