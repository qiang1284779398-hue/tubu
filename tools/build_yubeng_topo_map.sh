#!/usr/bin/env bash
set -euo pipefail

mkdir -p build/yubeng-map
WORK="build/yubeng-map"
SOURCE="https://copernicus-dem-30m.s3.amazonaws.com/Copernicus_DSM_COG_10_N28_00_E098_00_DEM/Copernicus_DSM_COG_10_N28_00_E098_00_DEM.tif"

echo "Checking Copernicus GLO-30 source..."
gdalinfo "/vsicurl/${SOURCE}" >/dev/null

echo "Cropping the Yubeng route area and projecting to Web Mercator..."
gdalwarp -overwrite -multi -wo NUM_THREADS=ALL_CPUS \
  -te_srs EPSG:4326 -te 98.70 28.32 98.92 28.45 \
  -t_srs EPSG:3857 -tr 5 5 -r bilinear -dstnodata -9999 \
  -co COMPRESS=DEFLATE "/vsicurl/${SOURCE}" "${WORK}/dem.tif"

echo "Creating shaded relief and 20 m / 100 m contour lines..."
gdaldem hillshade "${WORK}/dem.tif" "${WORK}/hillshade.tif" \
  -z 1.5 -s 1 -az 315 -alt 45 -compute_edges -co COMPRESS=DEFLATE

gdal_contour -a elev -i 20 -f GPKG "${WORK}/dem.tif" "${WORK}/contours_20m.gpkg"
gdal_contour -a elev -i 100 -f GPKG "${WORK}/dem.tif" "${WORK}/contours_100m.gpkg"

python3 - "${WORK}" <<'PY'
import json, subprocess, sys
from pathlib import Path
work=Path(sys.argv[1])
info=json.loads(subprocess.check_output(["gdalinfo","-json",str(work/"dem.tif")]))
ll=info["cornerCoordinates"]["lowerLeft"]
ur=info["cornerCoordinates"]["upperRight"]
width,height=info["size"]
extent=[str(ll[0]),str(ll[1]),str(ur[0]),str(ur[1])]
size=[str(width),str(height)]
for interval in (20,100):
    out=work/f"contour_mask_{interval}.tif"
    subprocess.check_call([
        "gdal_rasterize","-burn","255","-init","0","-ot","Byte","-of","GTiff",
        "-te",*extent,"-ts",*size,str(work/f"contours_{interval}m.gpkg"),str(out)
    ])
PY

echo "Styling terrain and contour lines..."
python3 - "${WORK}" <<'PY'
import sys
from pathlib import Path
import numpy as np
import rasterio

w=Path(sys.argv[1])
with rasterio.open(w/"dem.tif") as dem_src:
    elev=dem_src.read(1).astype("float32")
    profile=dem_src.profile.copy()
    nodata=dem_src.nodata
with rasterio.open(w/"hillshade.tif") as src:
    shade=src.read(1).astype("float32")
with rasterio.open(w/"contour_mask_20.tif") as src:
    minor=src.read(1)>0
with rasterio.open(w/"contour_mask_100.tif") as src:
    major=src.read(1)>0

# Muted outdoor topographic palette: alpine green -> warm rock.
t=np.clip((elev-2600.0)/3000.0,0.0,1.0)
low=np.array([205.0,220.0,192.0],dtype="float32")
mid=np.array([225.0,216.0,190.0],dtype="float32")
high=np.array([235.0,228.0,214.0],dtype="float32")
rgb=np.empty((*elev.shape,3),dtype="float32")
for c in range(3):
    first=low[c]*(1-np.minimum(t,0.55)/0.55)+mid[c]*(np.minimum(t,0.55)/0.55)
    second=mid[c]*(1-np.maximum(t-0.55,0)/0.45)+high[c]*(np.maximum(t-0.55,0)/0.45)
    base=np.where(t<=0.55,first,second)
    light=0.68+0.48*(shade/255.0)
    rgb[:,:,c]=base*light
# Thin brown minor contours and stronger 100 m index contours.
rgb[minor]=rgb[minor]*0.62+np.array([125,101,72],dtype="float32")*0.38
rgb[major]=np.array([111,76,49],dtype="float32")
invalid=(elev==nodata) if nodata is not None else ~np.isfinite(elev)
rgb[invalid]=np.array([232,232,225],dtype="float32")
profile.update(driver="GTiff",count=3,dtype="uint8",nodata=None,compress="DEFLATE",photometric="RGB")
with rasterio.open(w/"yubeng_topo.tif","w",**profile) as dst:
    for band in range(3):
        dst.write(np.clip(rgb[:,:,band],0,255).astype("uint8"),band+1)
PY

echo "Packaging raster MBTiles for TrailKit..."
gdal_translate -of MBTILES -co TILE_FORMAT=PNG \
  -co NAME="雨崩等高线地形图" \
  -co DESCRIPTION="Copernicus GLO-30 shaded relief with 20 m contours and 100 m index contours; route area extract" \
  -co TYPE=baselayer -co VERSION=1.3 \
  "${WORK}/yubeng_topo.tif" "${WORK}/yubeng_topo.mbtiles"

gdaladdo -r average "${WORK}/yubeng_topo.mbtiles" 2 4 8 16 32

sqlite3 "${WORK}/yubeng_topo.mbtiles" <<'SQL'
INSERT OR REPLACE INTO metadata(name,value) VALUES
('attribution','Produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018, provided under COPERNICUS by the European Union and ESA; all rights reserved.'),
('minzoom','10'),
('maxzoom','15');
SQL

sqlite3 "${WORK}/yubeng_topo.mbtiles" "SELECT name,value FROM metadata WHERE name IN ('name','format','minzoom','maxzoom','bounds','center','attribution');"
ls -lh "${WORK}/yubeng_topo.mbtiles"
