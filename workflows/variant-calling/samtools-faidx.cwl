#!/usr/bin/env cwl-runner
cwlVersion: v1.2
class: CommandLineTool
label: "samtools faidx — index a reference FASTA (.fai)"

requirements:
  DockerRequirement:
    dockerPull: "staphb/samtools:1.17"
  InitialWorkDirRequirement:
    listing:
      - $(inputs.reference)

baseCommand: [samtools, faidx]

inputs:
  reference:
    type: File
    label: "Reference genome (FASTA)"
    inputBinding:
      position: 1
      valueFrom: $(self.basename)

outputs:
  indexed_reference:
    type: File
    label: "Reference with its .fai index attached as a secondaryFile"
    outputBinding:
      glob: $(inputs.reference.basename)
    secondaryFiles:
      - .fai
