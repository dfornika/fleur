#!/usr/bin/env cwl-runner
cwlVersion: v1.2
class: CommandLineTool
label: "summarize — collate BLAST hits into a gene presence/absence report"
doc: |
  A small Python tool that reads the tabular BLAST results gathered from the
  scattered blastn step (one .tsv per query gene) and writes a presence/absence
  report. A gene is called "present" when its best hit meets both the identity
  and coverage thresholds; a gene with an empty result file is "absent".

  This step runs locally (no DockerRequirement), so the workflow exercises a
  mix of containerized (blast) and local (python) tools. The helper script is
  shipped with the tool via InitialWorkDirRequirement rather than an external
  file.

requirements:
  InitialWorkDirRequirement:
    listing:
      - entryname: summarize.py
        entry: |
          import sys

          def parse_args(argv):
              min_id, min_cov, tables = 90.0, 60.0, []
              i = 0
              while i < len(argv):
                  a = argv[i]
                  if a == "--min-identity":
                      i += 1; min_id = float(argv[i])
                  elif a == "--min-coverage":
                      i += 1; min_cov = float(argv[i])
                  else:
                      tables.append(a)
                  i += 1
              return min_id, min_cov, tables

          def best_hit(path):
              # outfmt 6: qseqid sseqid pident length qlen qcovs evalue bitscore
              best = None
              with open(path) as fh:
                  for line in fh:
                      line = line.strip()
                      if not line:
                          continue
                      f = line.split("\t")
                      bitscore = float(f[7])
                      if best is None or bitscore > best["bitscore"]:
                          best = {"subject": f[1], "pident": float(f[2]),
                                  "qcovs": float(f[5]), "evalue": f[6],
                                  "bitscore": bitscore}
              return best

          def gene_name(path):
              base = path.rsplit("/", 1)[-1]
              return base[:-4] if base.endswith(".tsv") else base

          def main():
              min_id, min_cov, tables = parse_args(sys.argv[1:])
              rows = []
              for path in sorted(tables, key=gene_name):
                  gene = gene_name(path)
                  hit = best_hit(path)
                  if hit and hit["pident"] >= min_id and hit["qcovs"] >= min_cov:
                      rows.append((gene, "present", hit["pident"], hit["qcovs"],
                                   hit["subject"], hit["evalue"]))
                  elif hit:
                      rows.append((gene, "partial", hit["pident"], hit["qcovs"],
                                   hit["subject"], hit["evalue"]))
                  else:
                      rows.append((gene, "absent", 0.0, 0.0, "-", "-"))

              header = ["gene", "status", "pct_identity", "pct_coverage",
                        "subject", "evalue"]
              with open("gene_report.tsv", "w") as out:
                  out.write("\t".join(header) + "\n")
                  for r in rows:
                      out.write("\t".join(str(x) for x in r) + "\n")

              # Human-readable summary to stderr (shows up in the run log).
              present = sum(1 for r in rows if r[1] == "present")
              partial = sum(1 for r in rows if r[1] == "partial")
              absent = sum(1 for r in rows if r[1] == "absent")
              sys.stderr.write(
                  "gene panel: {} present, {} partial, {} absent "
                  "(identity>={}%, coverage>={}%)\n".format(
                      present, partial, absent, min_id, min_cov))

          if __name__ == "__main__":
              main()

baseCommand: [python3, summarize.py]

inputs:
  hit_tables:
    type: File[]
    label: "Tabular BLAST results, one per query gene"
    inputBinding:
      position: 1
  min_identity:
    type: float
    default: 90.0
    label: "Minimum % identity to call a gene present"
    inputBinding:
      prefix: "--min-identity"
  min_coverage:
    type: float
    default: 80.0
    label: "Minimum % query coverage to call a gene present"
    inputBinding:
      prefix: "--min-coverage"

outputs:
  report:
    type: File
    label: "Presence/absence report (TSV)"
    outputBinding:
      glob: gene_report.tsv
