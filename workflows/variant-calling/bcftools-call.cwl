#!/usr/bin/env cwl-runner
cwlVersion: v1.2
class: CommandLineTool
label: "bcftools call — call variants from genotype likelihoods"

requirements:
  DockerRequirement:
    dockerPull: "staphb/bcftools:1.17"

baseCommand: [bcftools, call]

arguments:
  - "-m"                      # multiallelic-caller
  - "-v"                      # variant sites only
  - "-O"
  - "v"                       # output VCF
  - prefix: "-o"
    valueFrom: $(inputs.pileup.nameroot).vcf

inputs:
  pileup:
    type: File
    label: "Genotype likelihoods (BCF/VCF) from bcftools mpileup"
    inputBinding:
      position: 1

outputs:
  variants:
    type: File
    label: "Called variants (VCF)"
    outputBinding:
      glob: $(inputs.pileup.nameroot).vcf
