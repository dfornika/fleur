cwlVersion: v1.2
class: CommandLineTool

# A real CommandLineTool: `cat file1 file2` with the result captured via stdout
# redirection into a File output. Exercises input File staging, positional
# argument ordering, stdout capture, and glob-based output collection end to end.
baseCommand: cat

inputs:
  file1:
    type: File
    inputBinding:
      position: 1
  file2:
    type: File
    inputBinding:
      position: 2

stdout: combined.txt

outputs:
  out:
    type: File
    outputBinding:
      glob: combined.txt
