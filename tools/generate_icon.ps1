<#
.SYNOPSIS
    Builds every Android launcher icon resource for Chameleon from a single square image.

.DESCRIPTION
    Generates, from <Source>:
      * res/drawable-nodpi/ic_launcher_background.png  (blurred + darkened cover, adaptive background)
      * res/drawable-nodpi/ic_launcher_foreground.png  (artwork inside the 70% safe area, adaptive foreground)
      * res/mipmap-{m,h,x,xx,xxx}dpi/ic_launcher.png        (rounded square, transparent corners)
      * res/mipmap-{m,h,x,xx,xxx}dpi/ic_launcher_round.png  (circle, transparent corners)

    The monochrome (themed icon) layer is a vector and is not touched here.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools/generate_icon.ps1
    powershell -ExecutionPolicy Bypass -File tools/generate_icon.ps1 -Source new-icon.png
    # rebuild the density bitmaps and the README showcase from the committed adaptive layers,
    # without needing the original artwork at all:
    powershell -ExecutionPolicy Bypass -File tools/generate_icon.ps1 -FromLayers
#>
param(
    [string]$Source = "icon.jpg",
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [double]$ArtFraction = 0.70,
    [double]$BackdropDarken = 0.86,
    [switch]$FromLayers
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$sourcePath = if ([System.IO.Path]::IsPathRooted($Source)) { $Source } else { Join-Path $Root $Source }
$res = Join-Path $Root 'app\src\main\res'
$bgLayerPath = Join-Path $res 'drawable-nodpi\ic_launcher_background.png'
$fgLayerPath = Join-Path $res 'drawable-nodpi\ic_launcher_foreground.png'

# Without the original artwork we can still rebuild everything from the two adaptive layers that are
# committed in the repository.
if (-not (Test-Path $sourcePath) -and (Test-Path $bgLayerPath) -and (Test-Path $fgLayerPath)) {
    Write-Host "source '$sourcePath' not found - rebuilding from the committed adaptive layers"
    $FromLayers = $true
}
$src = if ($FromLayers) { $null } else { [System.Drawing.Bitmap]::FromFile($sourcePath) }
if (-not $FromLayers -and $null -eq $src) { throw "icon source not found: $sourcePath" }

function New-Graphics([System.Drawing.Bitmap]$bmp) {
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    return $g
}

function Draw-Cover($g, $img, [double]$x, [double]$y, [double]$w, [double]$h) {
    $s = [Math]::Max($w / $img.Width, $h / $img.Height)
    $dw = $img.Width * $s
    $dh = $img.Height * $s
    $g.DrawImage(
        $img,
        [System.Drawing.RectangleF]::new(
            [float]($x + ($w - $dw) / 2),
            [float]($y + ($h - $dh) / 2),
            [float]$dw,
            [float]$dh
        )
    )
}

function Rounded-Path([double]$x, [double]$y, [double]$w, [double]$h, [double]$rad) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $rad * 2
    $p.AddArc([float]$x, [float]$y, [float]$d, [float]$d, 180, 90)
    $p.AddArc([float]($x + $w - $d), [float]$y, [float]$d, [float]$d, 270, 90)
    $p.AddArc([float]($x + $w - $d), [float]($y + $h - $d), [float]$d, [float]$d, 0, 90)
    $p.AddArc([float]$x, [float]($y + $h - $d), [float]$d, [float]$d, 90, 90)
    $p.CloseFigure()
    return $p
}

# Upscaling a small bitmap samples past its border; clamping one extra pixel keeps the result opaque.
function Pad-Clamp([System.Drawing.Bitmap]$bmp) {
    $w = $bmp.Width
    $h = $bmp.Height
    $p = New-Object System.Drawing.Bitmap ($w + 2), ($h + 2)
    for ($y = 0; $y -lt $h + 2; $y++) {
        for ($x = 0; $x -lt $w + 2; $x++) {
            $sx = [Math]::Min($w - 1, [Math]::Max(0, $x - 1))
            $sy = [Math]::Min($h - 1, [Math]::Max(0, $y - 1))
            $p.SetPixel($x, $y, $bmp.GetPixel($sx, $sy))
        }
    }
    return $p
}

$baseR = 0; $baseG = 0; $baseB = 0; $n = 0
if (-not $FromLayers) {
    for ($y = 0; $y -lt $src.Height; $y += 8) {
        for ($x = 0; $x -lt $src.Width; $x += 8) {
            $c = $src.GetPixel($x, $y)
            $baseR += $c.R; $baseG += $c.G; $baseB += $c.B; $n++
        }
    }
}
$base = if ($n -gt 0) {
    [System.Drawing.Color]::FromArgb(255, [int]($baseR / $n), [int]($baseG / $n), [int]($baseB / $n))
} else {
    [System.Drawing.Color]::FromArgb(255, 32, 32, 32)
}
if ($FromLayers) {
    Write-Host "rebuilding from adaptive layers"
} else {
    Write-Host ("icon source {0} ({1}x{2}), base colour {3},{4},{5}" -f $sourcePath, $src.Width, $src.Height, $base.R, $base.G, $base.B)
}

function New-Backdrop([int]$size) {
    if ($FromLayers) {
        $layer = [System.Drawing.Bitmap]::FromFile($bgLayerPath)
        $out = New-Object System.Drawing.Bitmap $size, $size
        $og = New-Graphics $out
        $og.DrawImage($layer, [System.Drawing.RectangleF]::new(0, 0, [float]$size, [float]$size))
        $og.Dispose(); $layer.Dispose()
        return $out
    }
    $sw = [int]($size / 10); $sh = [int]($size / 10)
    $small = New-Object System.Drawing.Bitmap $sw, $sh
    $sg = New-Graphics $small
    Draw-Cover $sg $src 0 0 $sw $sh
    $sg.Dispose()
    $padded = Pad-Clamp $small
    $out = New-Object System.Drawing.Bitmap $size, $size
    $og = New-Graphics $out
    $og.Clear($base)
    $scale = $size / $sw
    $og.DrawImage(
        $padded,
        [System.Drawing.RectangleF]::new(
            [float](-$scale), [float](-$scale),
            [float]($size + 2 * $scale), [float]($size + 2 * $scale)
        )
    )
    $og.Dispose(); $small.Dispose(); $padded.Dispose()

    $cm = New-Object System.Drawing.Imaging.ColorMatrix
    $cm.Matrix00 = $BackdropDarken; $cm.Matrix11 = $BackdropDarken
    $cm.Matrix22 = $BackdropDarken; $cm.Matrix33 = 1; $cm.Matrix44 = 1
    $ia = New-Object System.Drawing.Imaging.ImageAttributes
    $ia.SetColorMatrix($cm)
    $darkened = New-Object System.Drawing.Bitmap $size, $size
    $dg = New-Graphics $darkened
    $rect = [System.Drawing.Rectangle]::new(0, 0, $size, $size)
    $dg.DrawImage($out, $rect, 0, 0, $size, $size, [System.Drawing.GraphicsUnit]::Pixel, $ia)
    $dg.Dispose(); $ia.Dispose(); $out.Dispose()
    return $darkened
}

# Draws the artwork layer for one icon: either the original file or the committed foreground layer.
function Draw-Artwork($g, [int]$size) {
    if ($FromLayers) {
        $layer = [System.Drawing.Bitmap]::FromFile($fgLayerPath)
        $g.DrawImage($layer, [System.Drawing.RectangleF]::new(0, 0, [float]$size, [float]$size))
        $layer.Dispose()
    } else {
        $artSize = $size * $ArtFraction
        $artOffset = ($size - $artSize) / 2
        Draw-Cover $g $src $artOffset $artOffset $artSize $artSize
    }
}

$adaptiveSize = 432
$backdrop = New-Backdrop $adaptiveSize
$backdrop.Save($bgLayerPath, [System.Drawing.Imaging.ImageFormat]::Png)

if (-not $FromLayers) {
    $fg = New-Object System.Drawing.Bitmap $adaptiveSize, $adaptiveSize
    $g = New-Graphics $fg
    $art = $adaptiveSize * $ArtFraction
    $offset = ($adaptiveSize - $art) / 2
    $path = Rounded-Path $offset $offset $art $art ($art * 0.085)
    $g.SetClip($path)
    Draw-Cover $g $src $offset $offset $art $art
    $g.ResetClip(); $g.Dispose(); $path.Dispose()
    $fg.Save($fgLayerPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $fg.Dispose()
}

$sizes = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
foreach ($key in $sizes.Keys) {
    $size = [int]$sizes[$key]
    $dir = Join-Path $res "mipmap-$key"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    foreach ($round in @($false, $true)) {
        $out = New-Object System.Drawing.Bitmap $size, $size
        $g = New-Graphics $out
        if ($round) {
            $shape = New-Object System.Drawing.Drawing2D.GraphicsPath
            $shape.AddEllipse(0, 0, [float]$size, [float]$size)
        } else {
            $shape = Rounded-Path 0 0 $size $size ($size * 0.22)
        }
        $g.SetClip($shape)
        $g.DrawImage($backdrop, [System.Drawing.RectangleF]::new(0, 0, [float]$size, [float]$size))
        if ($FromLayers) {
            Draw-Artwork $g $size
        } else {
            $artSize = $size * $ArtFraction
            $artOffset = ($size - $artSize) / 2
            $artPath = Rounded-Path $artOffset $artOffset $artSize $artSize ($artSize * 0.085)
            $g.SetClip($artPath)
            Draw-Cover $g $src $artOffset $artOffset $artSize $artSize
            $artPath.Dispose()
        }
        $g.ResetClip(); $g.Dispose(); $shape.Dispose()
        $name = if ($round) { 'ic_launcher_round.png' } else { 'ic_launcher.png' }
        $out.Save((Join-Path $dir $name), [System.Drawing.Imaging.ImageFormat]::Png)
        $out.Dispose()
    }
}

$backdrop.Dispose()
if ($null -ne $src) { $src.Dispose() }

# ---- README showcase: the icon as a launcher would draw it (squircle mask) ----
$showcaseSize = 320
$showcaseDir = Join-Path $Root 'docs\images'
New-Item -ItemType Directory -Force -Path $showcaseDir | Out-Null
$layerBg = [System.Drawing.Bitmap]::FromFile($bgLayerPath)
$layerFg = [System.Drawing.Bitmap]::FromFile($fgLayerPath)
$showcase = New-Object System.Drawing.Bitmap $showcaseSize, $showcaseSize
$sg = New-Graphics $showcase
$mask = Rounded-Path 0 0 $showcaseSize $showcaseSize ($showcaseSize * 0.235)
$sg.SetClip($mask)
$sg.DrawImage($layerBg, [System.Drawing.RectangleF]::new(0, 0, [float]$showcaseSize, [float]$showcaseSize))
$sg.DrawImage($layerFg, [System.Drawing.RectangleF]::new(0, 0, [float]$showcaseSize, [float]$showcaseSize))
$sg.ResetClip()
$sg.Dispose(); $mask.Dispose(); $layerBg.Dispose(); $layerFg.Dispose()
$showcase.Save((Join-Path $showcaseDir 'icon.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$showcase.Dispose()
Write-Host ("icon showcase written to {0}" -f (Join-Path $showcaseDir 'icon.png'))

# ---- verification: the background must be opaque, the artwork must be present ----
$checkBg = [System.Drawing.Bitmap]::FromFile((Join-Path $res 'drawable-nodpi\ic_launcher_background.png'))
$checkFg = [System.Drawing.Bitmap]::FromFile((Join-Path $res 'drawable-nodpi\ic_launcher_foreground.png'))
$minAlpha = 255
foreach ($pt in @(@(0, 0), @(431, 0), @(0, 431), @(431, 431), @(216, 0), @(0, 216))) {
    $a = $checkBg.GetPixel($pt[0], $pt[1]).A
    if ($a -lt $minAlpha) { $minAlpha = $a }
}
Write-Host ("background min alpha {0} (must be 255), foreground corner alpha {1} (must be 0), artwork alpha {2}" -f `
        $minAlpha, $checkFg.GetPixel(0, 0).A, $checkFg.GetPixel(216, 216).A)
if ($minAlpha -ne 255 -or $checkFg.GetPixel(0, 0).A -ne 0) { throw "icon verification failed" }
$checkBg.Dispose(); $checkFg.Dispose()
Write-Host "icon resources regenerated"
