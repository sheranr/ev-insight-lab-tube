$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$srcPath = Resolve-Path (Join-Path $scriptDir '..\icon.png') -ErrorAction Stop
$src = $srcPath.Path
if (-not (Test-Path $src)) { Write-Error "icon.png not found at $src"; exit 1 }

$sizes = @{
    'app/src/main/res/mipmap-mdpi' = 48
    'app/src/main/res/mipmap-hdpi' = 72
    'app/src/main/res/mipmap-xhdpi' = 96
    'app/src/main/res/mipmap-xxhdpi' = 144
    'app/src/main/res/mipmap-xxxhdpi' = 192
}

Add-Type -AssemblyName System.Drawing

foreach ($kv in $sizes.GetEnumerator()) {
    $dir = $kv.Key
    $size = [int]$kv.Value
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }

    $img = [System.Drawing.Image]::FromFile($src)
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)
    $g.DrawImage($img, 0, 0, $size, $size)

    $out1 = Join-Path $dir 'ic_launcher.png'
    $out2 = Join-Path $dir 'ic_launcher_round.png'
    $bmp.Save($out1, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Save($out2, [System.Drawing.Imaging.ImageFormat]::Png)

    $g.Dispose(); $bmp.Dispose(); $img.Dispose();
    Write-Output "Wrote $out1 and $out2"
}

# create large drawable foreground for adaptive icon (432px)
$drawableDir = 'app/src/main/res/drawable'
if (-not (Test-Path $drawableDir)) { New-Item -ItemType Directory -Path $drawableDir | Out-Null }
$img = [System.Drawing.Image]::FromFile($src)
$size = 432
$bmp = New-Object System.Drawing.Bitmap $size, $size
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
$g.Clear([System.Drawing.Color]::Transparent)
$g.DrawImage($img, 0, 0, $size, $size)
$out = Join-Path $drawableDir 'ic_launcher_foreground.png'
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose(); $img.Dispose();
Write-Output "Wrote $out"
