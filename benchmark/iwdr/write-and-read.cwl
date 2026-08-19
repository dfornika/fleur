cwlVersion: v1.2
class: CommandLineTool

# InitialWorkDirRequirement stages a literal file into the working directory,
# which the tool then reads. Exercises Dirent staging (entryname + literal entry
# content) and running the command in runtime.outdir.
requirements:
  InitialWorkDirRequirement:
    listing:
      - entryname: greeting.txt
        entry: "hello from the initial work dir\n"

baseCommand: [cat, greeting.txt]

inputs: []

stdout: out.txt

outputs:
  out:
    type: File
    outputBinding:
      glob: out.txt
