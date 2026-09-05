#!/usr/bin/env python3
"""Generate the synthetic inputs for the gene-detection demo workflow.

Deterministic (fixed seed) so the committed FASTA files are reproducible. The
generated genome embeds three of the five panel genes at known positions:

  geneA  - embedded verbatim              -> detected, 100% identity, full length
  geneB  - embedded with a few SNPs       -> detected, <100% identity
  geneC  - only the first ~60% embedded   -> detected, partial coverage
  geneD  - never embedded (negative ctrl) -> not detected
  geneE  - never embedded (negative ctrl) -> not detected

Run from the workflow directory:

    python3 scripts/generate_data.py

Writes data/genome.fasta and data/genes/gene{A..E}.fasta.
"""
import os
import random

SEED = 20260905
GENE_LEN = 300
BACKBONE_CHUNK = 800  # random filler between embedded genes

random.seed(SEED)

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(HERE, "data")
GENES = os.path.join(DATA, "genes")


def rand_seq(n):
    return "".join(random.choice("ACGT") for _ in range(n))


def mutate(seq, n_snps):
    """Return `seq` with `n_snps` single-base substitutions at spread positions."""
    s = list(seq)
    step = max(1, len(s) // (n_snps + 1))
    for k in range(n_snps):
        i = min(len(s) - 1, step * (k + 1))
        alt = [b for b in "ACGT" if b != s[i]]
        s[i] = random.choice(alt)
    return "".join(s)


def wrap(seq, width=70):
    return "\n".join(seq[i:i + width] for i in range(0, len(seq), width))


def write_fasta(path, header, seq):
    with open(path, "w") as fh:
        fh.write(">{}\n{}\n".format(header, wrap(seq)))


def main():
    os.makedirs(GENES, exist_ok=True)

    # The five panel genes (independent random sequences).
    geneA = rand_seq(GENE_LEN)
    geneB = rand_seq(GENE_LEN)
    geneC = rand_seq(GENE_LEN)
    geneD = rand_seq(GENE_LEN)
    geneE = rand_seq(GENE_LEN)

    panel = {
        "geneA": (geneA, "resistance gene, present (exact copy in genome)"),
        "geneB": (geneB, "resistance gene, present with point mutations"),
        "geneC": (geneC, "marker gene, present but truncated in genome"),
        "geneD": (geneD, "virulence gene, absent (negative control)"),
        "geneE": (geneE, "virulence gene, absent (negative control)"),
    }
    for name, (seq, desc) in panel.items():
        write_fasta(os.path.join(GENES, name + ".fasta"),
                    "{} {}".format(name, desc), seq)

    # Build the genome: backbone | geneA (exact) | backbone |
    # geneB (5 SNPs) | backbone | geneC[:180] (truncated) | backbone
    geneB_variant = mutate(geneB, 5)
    geneC_partial = geneC[:180]
    genome = (
        rand_seq(BACKBONE_CHUNK)
        + geneA
        + rand_seq(BACKBONE_CHUNK)
        + geneB_variant
        + rand_seq(BACKBONE_CHUNK)
        + geneC_partial
        + rand_seq(BACKBONE_CHUNK)
    )
    write_fasta(os.path.join(DATA, "genome.fasta"),
                "synthetic_contig_1 demo genome for gene detection", genome)

    print("genome.fasta: {} bp".format(len(genome)))
    for name in panel:
        print("  {}.fasta: {} bp".format(name, GENE_LEN))


if __name__ == "__main__":
    main()
