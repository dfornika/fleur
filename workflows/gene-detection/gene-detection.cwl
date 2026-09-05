#!/usr/bin/env cwl-runner
cwlVersion: v1.2
class: Workflow
label: "Gene detection — screen a gene panel against a genome with BLAST"
doc: |
  A small but non-trivial bioinformatics pipeline: detect which genes from a
  panel are present in a genome/assembly.

    reference genome ── makeblastdb ── database ─┐
                                                 ├─ blastn (scatter over panel) ─ hits[] ─ summarize ─ report.tsv
    gene panel (File[]) ───── scatter ───────────┘

  Features exercised beyond a basic linear tool run:
    * Workflow DAG with three steps
    * scatter/gather: one blastn task per query gene, hits gathered into an array
    * secondaryFiles: the multi-file BLAST database threaded from makeblastdb
      into blastn as a single File + its index siblings
    * a mix of containerized (blast) and local (python) tools
    * InitialWorkDirRequirement to ship the summarize script

requirements:
  ScatterFeatureRequirement: {}

inputs:
  reference:
    type: File
    label: "Genome / assembly to screen (FASTA)"
  query_genes:
    type: File[]
    label: "Gene panel — one FASTA per query gene"
  min_identity:
    type: float
    default: 90.0
    label: "Minimum % identity to call a gene present"
  min_coverage:
    type: float
    default: 80.0
    label: "Minimum % query coverage to call a gene present"

outputs:
  report:
    type: File
    label: "Gene presence/absence report (TSV)"
    outputSource: summarize/report

steps:
  build_db:
    run: makeblastdb.cwl
    label: "Build BLAST database from the genome"
    in:
      reference: reference
    out: [database]

  search:
    run: blastn.cwl
    label: "BLAST each panel gene against the genome"
    scatter: query
    in:
      query: query_genes
      database: build_db/database
    out: [hits]

  summarize:
    run: summarize.cwl
    label: "Collate hits into a presence/absence report"
    in:
      hit_tables: search/hits
      min_identity: min_identity
      min_coverage: min_coverage
    out: [report]
