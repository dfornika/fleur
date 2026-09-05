#!/usr/bin/env cwl-runner
cwlVersion: v1.2
class: CommandLineTool
label: "makeblastdb — build a nucleotide BLAST database from a genome FASTA"
doc: |
  Wraps `makeblastdb -dbtype nucl`. A BLAST nucleotide database is a *set* of
  files that share a base name (genome.nsq, genome.nhr, genome.nin, ...). We
  model it in CWL as a single File (the .nsq) carrying the rest as
  `secondaryFiles`, so the whole database travels together into `blastn`.

requirements:
  DockerRequirement:
    dockerPull: "ncbi/blast:2.16.0"

baseCommand: [makeblastdb]

arguments:
  - prefix: "-dbtype"
    valueFrom: "nucl"
  - prefix: "-out"
    # Name the database after the input genome (e.g. genome.fasta -> genome.*).
    valueFrom: $(inputs.reference.nameroot)

inputs:
  reference:
    type: File
    label: "Genome / assembly to index (FASTA)"
    inputBinding:
      prefix: "-in"

outputs:
  database:
    type: File
    label: "BLAST nucleotide database (base = .nsq, siblings as secondaryFiles)"
    outputBinding:
      glob: $(inputs.reference.nameroot).nsq
    secondaryFiles:
      - ^.ndb
      - ^.nhr
      - ^.nin
      - ^.njs
      - ^.not
      - ^.ntf
      - ^.nto
