# Gene detection — BLAST-based screening demo

A small but non-trivial pipeline that screens a **gene panel** against a
**genome/assembly** with BLAST and reports which genes are present. It is meant
as a realistic demo/benchmark that exercises more than the basics — a good
vehicle for testing execution feedback and logging.

```
reference genome ── makeblastdb ── database ─┐
                                             ├─ blastn (scatter over panel) ─ hits[] ─ summarize ─ report.tsv
gene panel (File[]) ───── scatter ───────────┘
```

## What it exercises

- **Workflow DAG** with three steps (`makeblastdb → blastn → summarize`).
- **Scatter/gather** — one `blastn` task per query gene; hits gathered into an
  array for the summarize step.
- **secondaryFiles** — a BLAST nucleotide database is a *set* of files
  (`genome.nsq`, `genome.nhr`, `genome.nin`, …). It is modelled as one `File`
  (the `.nsq`) carrying the siblings as `secondaryFiles`, so the whole database
  travels from `makeblastdb` into `blastn` as a unit.
- **A mix of containerized and local tools** — `makeblastdb`/`blastn` run in the
  `ncbi/blast` container; `summarize` is a local Python tool.
- **InitialWorkDirRequirement** — the `summarize` step ships its Python helper
  via an inline IWDR listing rather than an external script.
- **valueFrom / arguments**, `stdout` capture, and output globs.

## Files

| File | Purpose |
|------|---------|
| `gene-detection.cwl` | the workflow |
| `makeblastdb.cwl` | build a nucleotide BLAST DB from the genome |
| `blastn.cwl` | BLAST one query gene against the DB (scattered) |
| `summarize.cwl` | collate hits into a presence/absence TSV report |
| `gene-detection-job.yml` | job inputs (genome + 5-gene panel) |
| `data/genome.fasta` | synthetic ~4 kb genome |
| `data/genes/gene{A..E}.fasta` | synthetic 300 bp panel genes |
| `scripts/generate_data.py` | regenerates the synthetic data (deterministic) |

## Synthetic data

`scripts/generate_data.py` builds a ~4 kb genome that embeds three of the five
panel genes at known positions, so results are deterministic and meaningful:

| gene | in genome | expected call |
|------|-----------|---------------|
| geneA | embedded verbatim | **present** — 100% identity, 100% coverage |
| geneB | embedded with 5 SNPs | **present** — ~98% identity, 100% coverage |
| geneC | first ~60% embedded | **partial** — 100% identity, 60% coverage |
| geneD | absent | **absent** (negative control) |
| geneE | absent | **absent** (negative control) |

Regenerate with:

```bash
python3 scripts/generate_data.py
```

## Requirements

- Docker, with the BLAST image available:
  ```bash
  docker pull ncbi/blast:2.16.0
  ```
- `python3` on the host (for the local `summarize` step).

## Running

Build the uberjar once (from the repo root):

```bash
clojure -T:build uber
```

Then run **from this directory** so the job file's relative paths resolve:

```bash
cd workflows/gene-detection
java -jar ../../target/cwl-runner-0.1.0-standalone.jar \
  gene-detection.cwl gene-detection-job.yml
```

The final `report` is printed as a JSON object pointing at `gene_report.tsv`:

```
gene    status   pct_identity  pct_coverage  subject             evalue
geneA   present  100.0         100.0         synthetic_contig_1  8.91e-162
geneB   present  98.333        100.0         synthetic_contig_1  1.94e-153
geneC   partial  100.0         60.0          synthetic_contig_1  1.26e-95
geneD   absent   0.0           0.0           -                   -
geneE   absent   0.0           0.0           -                   -
```

> **Note on paths:** Fleur currently resolves a job file's relative `File`
> paths against the *current working directory*, not the job file's own
> directory — so run from this directory, or make the paths in
> `gene-detection-job.yml` absolute. (cwltool resolves them relative to the job
> file; aligning Fleur with that is a candidate improvement.)
