#!/usr/bin/env cwl-runner
cwlVersion: v1.2
class: Workflow
label: "Basic variant calling — bwa mem + samtools + bcftools"
doc: |
  A minimal short-read variant-calling pipeline:

    reference ─┬─ bwa index ─┐
               │             ├─ bwa mem ─ samtools sort ─ samtools index ─┐
    reads1/2 ──┘             ┘                                            ├─ bcftools mpileup ─ bcftools call ─ variants.vcf
    reference ── samtools faidx ─────────────────────────────────────────┘

  The dependency graph is a branching DAG (not strictly linear): `bwa index` and
  `samtools faidx` both derive from the reference and converge at
  `bcftools mpileup`. Fleur runs the steps in topological order.

inputs:
  reference:
    type: File
    label: "Reference genome (FASTA)"
  reads1:
    type: File
    label: "Reads, mate 1 (FASTQ)"
  reads2:
    type: File?
    label: "Reads, mate 2 (FASTQ); omit for single-end"

outputs:
  variants:
    type: File
    label: "Called variants (VCF)"
    outputSource: call/variants

steps:
  bwa_index:
    run: bwa-index.cwl
    in:
      reference: reference
    out: [indexed_reference]

  faidx:
    run: samtools-faidx.cwl
    in:
      reference: reference
    out: [indexed_reference]

  align:
    run: bwa-mem.cwl
    in:
      reference: bwa_index/indexed_reference
      reads1: reads1
      reads2: reads2
    out: [sam]

  sort:
    run: samtools-sort.cwl
    in:
      alignments: align/sam
    out: [sorted_bam]

  index_bam:
    run: samtools-index.cwl
    in:
      bam: sort/sorted_bam
    out: [indexed_bam]

  mpileup:
    run: bcftools-mpileup.cwl
    in:
      reference: faidx/indexed_reference
      alignments: index_bam/indexed_bam
    out: [pileup]

  call:
    run: bcftools-call.cwl
    in:
      pileup: mpileup/pileup
    out: [variants]
