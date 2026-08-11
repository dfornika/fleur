#!/usr/bin/env cwl-runner
cwlVersion: v1.2
class: CommandLineTool
label: "bcftools mpileup — pile up reads and compute genotype likelihoods"

requirements:
  DockerRequirement:
    dockerPull: "staphb/bcftools:1.17"

baseCommand: [bcftools, mpileup]

arguments:
  - prefix: "-f"
    valueFrom: $(inputs.reference.path)
  - "-O"
  - "u"                       # uncompressed BCF, streamed to `bcftools call`
  - prefix: "-o"
    valueFrom: $(inputs.alignments.nameroot).pileup.bcf

inputs:
  reference:
    type: File
    label: "faidx-indexed reference genome"
    secondaryFiles:
      - .fai
  alignments:
    type: File
    label: "Coordinate-sorted, indexed BAM"
    secondaryFiles:
      - .bai
    inputBinding:
      position: 1

outputs:
  pileup:
    type: File
    label: "Genotype likelihoods (BCF)"
    outputBinding:
      glob: $(inputs.alignments.nameroot).pileup.bcf
