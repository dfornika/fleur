#!/usr/bin/env cwl-runner
cwlVersion: v1.2
class: CommandLineTool
label: "bwa index — build the BWA FM-index for a reference genome"

# bwa index writes its index files (.amb/.ann/.bwt/.pac/.sa) next to the
# reference, so stage the reference into the (writable) working directory first
# and index it there.
requirements:
  DockerRequirement:
    dockerPull: "staphb/bwa:0.7.17"
  InitialWorkDirRequirement:
    listing:
      - $(inputs.reference)

baseCommand: [bwa, index]

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
    label: "Reference with its BWA index files attached as secondaryFiles"
    outputBinding:
      glob: $(inputs.reference.basename)
    secondaryFiles:
      - .amb
      - .ann
      - .bwt
      - .pac
      - .sa
