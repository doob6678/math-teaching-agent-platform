param(
    [Parameter(Mandatory = $true)][string]$SourcePath,
    [Parameter(Mandatory = $true)][string]$TargetPath
)

$ErrorActionPreference = "Stop"
$source = [System.IO.Path]::GetFullPath($SourcePath)
$target = [System.IO.Path]::GetFullPath($TargetPath)
if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "DOCX source does not exist: $source" }
if ([System.IO.Path]::GetExtension($source).ToLowerInvariant() -ne ".docx") { throw "Only DOCX can be rendered" }
New-Item -ItemType Directory -Force -Path ([System.IO.Path]::GetDirectoryName($target)) | Out-Null

# Word is used only as a local renderer for the caller-owned file. It preserves equations/layout that Apache POI cannot
# turn into visual pages, and it writes a temporary PDF rather than modifying the uploaded original.
$word = New-Object -ComObject Word.Application
$word.Visible = $false
$word.DisplayAlerts = 0
try {
    $document = $word.Documents.Open($source, $false, $true)
    try { $document.SaveAs2($target, 17) } finally { $document.Close($false) }
} finally {
    $word.Quit()
    [Runtime.InteropServices.Marshal]::FinalReleaseComObject($word) | Out-Null
}
if (-not (Test-Path -LiteralPath $target -PathType Leaf)) { throw "Word did not create PDF: $target" }
