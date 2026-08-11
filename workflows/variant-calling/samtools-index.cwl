#!/usr/bin/env cwl-runner
cwlVersion: v1.2
class: CommandLineTool
label: "samtools index — index a coordinate-sorted BAM (.bai)"

# samtools index writes <bam>.bai next to the BAM, so stage the BAM into the
# working directory and index it there.
requirements:
  DockerRequirement:
    dockerPull: "staphb/samtools:1.17"
  InitialWorkDirRequirement:
    listing:
      - $(inputs.bam)

baseCommand: [samtools, index]

inputs:
  bam:
    type: File
    label: "Coordinate-sorted BAM"
    inputBinding:
      position: 1
      valueFrom: $(self.basename)

outputs:
  indexed_bam:
    type: File
    label: "BAM with its .bai index attached as a secondaryFile"
    outputBinding:
      glob: $(inputs.bam.basename)
    secondaryFiles:
      - .bai
