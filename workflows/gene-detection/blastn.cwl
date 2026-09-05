#!/usr/bin/env cwl-runner
cwlVersion: v1.2
class: CommandLineTool
label: "blastn — align one query gene against a nucleotide database"
doc: |
  Runs `blastn` for a single query FASTA against a prebuilt BLAST database,
  emitting tabular (outfmt 6) hits. In the workflow this tool is *scattered*
  over the gene panel, so one blastn task runs per query gene.

  The result file is named after the query (geneA.fasta -> geneA.tsv); a query
  with no hits still produces an (empty) .tsv, which the summarize step reads as
  "not detected".

requirements:
  DockerRequirement:
    dockerPull: "ncbi/blast:2.16.0"

baseCommand: [blastn]

arguments:
  # Tabular output: query id, % identity, alignment length, query length,
  # query coverage per subject, e-value, bitscore.
  - prefix: "-outfmt"
    valueFrom: "6 qseqid sseqid pident length qlen qcovs evalue bitscore"
  - prefix: "-max_target_seqs"
    valueFrom: "5"

inputs:
  query:
    type: File
    label: "Query gene (FASTA)"
    inputBinding:
      prefix: "-query"
  database:
    type: File
    label: "BLAST nucleotide database (from makeblastdb)"
    # The whole database set must be staged alongside the .nsq base file.
    secondaryFiles:
      - ^.ndb
      - ^.nhr
      - ^.nin
      - ^.njs
      - ^.not
      - ^.ntf
      - ^.nto
    inputBinding:
      prefix: "-db"
      # blastn wants the shared base path, not the .nsq file itself.
      valueFrom: $(self.dirname)/$(self.nameroot)

stdout: $(inputs.query.nameroot).tsv

outputs:
  hits:
    type: File
    label: "Tabular BLAST hits for this query (outfmt 6)"
    outputBinding:
      glob: $(inputs.query.nameroot).tsv
