# Basic variant-calling pipeline (bwa + samtools + bcftools)

A minimal short-read variant-calling workflow written in CWL v1.2, intended as a
worked example for Fleur.

## Pipeline

```
reference ─┬─ bwa index ─────┐
           │                 ├─ bwa mem ─ samtools sort ─ samtools index ─┐
reads1/2 ──┘                 ┘                                            ├─ bcftools mpileup ─ bcftools call ─ variants.vcf
reference ── samtools faidx ─────────────────────────────────────────────┘
```

The dependency graph is a branching DAG (not strictly linear): `bwa index` and
`samtools faidx` both derive from the reference and converge at
`bcftools mpileup`. Fleur runs the steps in topological order.

## Files

| File | What it does |
| --- | --- |
| `bwa-index.cwl` | `bwa index` — build the BWA FM-index for the reference |
| `samtools-faidx.cwl` | `samtools faidx` — index the reference (`.fai`) |
| `bwa-mem.cwl` | `bwa mem` — align reads → SAM (stdout) |
| `samtools-sort.cwl` | `samtools sort` — coordinate-sort → BAM |
| `samtools-index.cwl` | `samtools index` — index the sorted BAM (`.bai`) |
| `bcftools-mpileup.cwl` | `bcftools mpileup` — genotype likelihoods → BCF |
| `bcftools-call.cwl` | `bcftools call` — call variants → VCF |
| `variant-calling.cwl` | the workflow wiring the tools together |
| `variant-calling-job.yml` | an example input job (edit the paths) |

Variant calling itself uses **bcftools** (the samtools project's variant caller;
`samtools mpileup` variant-calling is deprecated). The index files are carried
between steps as CWL `secondaryFiles` (`.amb/.ann/.bwt/.pac/.sa`, `.fai`, `.bai`).

## Running

Build the `cwl-runner` (see the repo root) and run:

```bash
cwl-runner workflows/variant-calling/variant-calling.cwl \
           workflows/variant-calling/variant-calling-job.yml
```

The final `variants` (VCF) output is printed to stdout as a JSON File object.

## Requirements

Each tool declares a `DockerRequirement` using [StaPH-B](https://hub.docker.com/u/staphb)
biocontainer images (`staphb/bwa:0.7.17`, `staphb/samtools:1.17`,
`staphb/bcftools:1.17`) — adjust the tags to suit your environment. Running the
pipeline therefore needs a reachable Docker daemon (and the ability to pull
those images), plus a reference FASTA and FASTQ reads. Nothing in this directory
is executed by Fleur's test suite; the tests only check that the CWL loads.
