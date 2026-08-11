#!/usr/bin/env cwl-runner
cwlVersion: v1.2
class: CommandLineTool
label: "bwa mem — align (paired-end) reads to a reference genome"

requirements:
  DockerRequirement:
    dockerPull: "staphb/bwa:0.7.17"

baseCommand: [bwa, mem]

arguments:
  - prefix: "-t"
    valueFrom: $(runtime.cores)

inputs:
  reference:
    type: File
    label: "BWA-indexed reference genome"
    # bwa mem needs the FM-index files alongside the FASTA.
    secondaryFiles:
      - .amb
      - .ann
      - .bwt
      - .pac
      - .sa
    inputBinding:
      position: 1
  reads1:
    type: File
    label: "Reads, mate 1 (FASTQ)"
    inputBinding:
      position: 2
  reads2:
    type: File?
    label: "Reads, mate 2 (FASTQ); omit for single-end"
    inputBinding:
      position: 3

# Capture the SAM stream produced on stdout.
stdout: $(inputs.reads1.nameroot).sam

outputs:
  sam:
    type: File
    label: "Alignments (SAM)"
    outputBinding:
      glob: $(inputs.reads1.nameroot).sam
