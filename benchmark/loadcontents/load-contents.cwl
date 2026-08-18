cwlVersion: v1.2
class: ExpressionTool

# `loadContents: true` asks the runner to read the first 64 KiB of the File and
# expose it as `inputs.f.contents` to expressions (CWL File loadContents). This
# probe counts the lines in the loaded contents. Fleur does not yet populate
# `.contents`, so today the expression sees `undefined` and fails / misreports.
requirements:
  InlineJavascriptRequirement: {}

inputs:
  f:
    type: File
    loadContents: true

outputs:
  line_count:
    type: int

expression: |
  ${
    var text = inputs.f.contents;
    var lines = text.split("\n").filter(function(s) { return s.length > 0; });
    return {line_count: lines.length};
  }
