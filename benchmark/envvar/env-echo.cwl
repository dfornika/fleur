cwlVersion: v1.2
class: CommandLineTool

# EnvVarRequirement: define an environment variable from an input and echo it
# back. Probes whether Fleur sets EnvVarRequirement variables in the tool's
# process environment. Classify by probing.
requirements:
  InlineJavascriptRequirement: {}
  EnvVarRequirement:
    envDef:
      GREETING: $(inputs.msg)

baseCommand: [sh, -c, 'printf "%s" "$GREETING"']

inputs:
  msg:
    type: string

stdout: out.txt

outputs:
  out:
    type: File
    outputBinding:
      glob: out.txt
