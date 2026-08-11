#!/usr/bin/env cwl-runner
cwlVersion: v1.2
class: CommandLineTool
label: "samtools sort — coordinate-sort alignments into BAM"

requirements:
  DockerRequirement:
    dockerPull: "staphb/samtools:1.17"

baseCommand: [samtools, sort]

arguments:
  - prefix: "-@"
    valueFrom: $(runtime.cores)
  - "-O"
  - "bam"
  - prefix: "-o"
    valueFrom: $(inputs.alignments.nameroot).sorted.bam

inputs:
  alignments:
    type: File
    label: "Alignments (SAM or BAM)"
    inputBinding:
      position: 1

outputs:
  sorted_bam:
    type: File
    label: "Coordinate-sorted BAM"
    outputBinding:
      glob: $(inputs.alignments.nameroot).sorted.bam
